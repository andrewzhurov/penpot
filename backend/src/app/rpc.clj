;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.metrics :as mtx]
   [app.rpc.retry :as retry]
   [app.rpc.rlimit :as rlimit]
   [app.util.async :as async]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.response :as yrs]))

(defn- default-handler
  [_]
  (p/rejected (ex/error :type :not-found)))

(defn- handle-response-transformation
  [response request mdata]
  (if-let [transform-fn (:transform-response mdata)]
    (p/do (transform-fn request response))
    (p/resolved response)))

(defn- handle-before-comple-hook
  [response mdata]
  (when-let [hook-fn (:before-complete mdata)]
    (ex/ignoring (hook-fn)))
  response)

(defn- rpc-query-handler
  "Ring handler that dispatches query requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [profile-id session-id params] :as request} respond raise]
  (letfn [(handle-response [result]
            (let [mdata (meta result)]
              (-> (yrs/response 200 result)
                  (handle-response-transformation request mdata))))]

    (let [type   (keyword (:type params))
          data   (into {::request request} params)
          data   (if profile-id
                   (assoc data :profile-id profile-id ::session-id session-id)
                   (dissoc data :profile-id))
          method (get methods type default-handler)]

      (-> (method data)
          (p/then handle-response)
          (p/then respond)
          (p/catch (fn [cause]
                     (let [context {:profile-id profile-id}]
                       (raise (ex/wrap-with-context cause context)))))))))

(defn- rpc-mutation-handler
  "Ring handler that dispatches mutation requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [profile-id session-id params] :as request} respond raise]
  (letfn [(handle-response [result]
            (let [mdata (meta result)]
              (p/-> (yrs/response 200 result)
                    (handle-response-transformation request mdata)
                    (handle-before-comple-hook mdata))))]

    (let [type   (keyword (:type params))
          data   (into {::request request} params)
          data   (if profile-id
                   (assoc data :profile-id profile-id ::session-id session-id)
                   (dissoc data :profile-id))

          method (get methods type default-handler)]
      (-> (method data)
          (p/then handle-response)
          (p/then respond)
          (p/catch (fn [cause]
                     (let [context {:profile-id profile-id}]
                       (raise (ex/wrap-with-context cause context)))))))))

(defn- rpc-command-handler
  "Ring handler that dispatches cmd requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [profile-id session-id params] :as request} respond raise]
  (letfn [(handle-response [result]
            (let [mdata (meta result)]
              (p/-> (yrs/response 200 result)
                    (handle-response-transformation request mdata)
                    (handle-before-comple-hook mdata))))]

    (let [cmd    (keyword (:command params))
          data   (into {::request request} params)
          data   (if profile-id
                   (assoc data :profile-id profile-id ::session-id session-id)
                   (dissoc data :profile-id))

          method (get methods cmd default-handler)]
      (-> (method data)
          (p/then handle-response)
          (p/then respond)
          (p/catch (fn [cause]
                     (let [context {:profile-id profile-id}]
                       (raise (ex/wrap-with-context cause context)))))))))

(defn- wrap-metrics
  "Wrap service method with metrics measurement."
  [{:keys [metrics ::metrics-id]} f mdata]
  (let [labels (into-array String [(::sv/name mdata)])]
    (fn [cfg params]
      (let [start (System/nanoTime)]
        (p/finally
          (f cfg params)
          (fn [_ _]
            (mtx/run! metrics
                      {:id metrics-id
                       :val (/ (- (System/nanoTime) start) 1000000)
                       :labels labels})))))))

(defn- wrap-dispatch
  "Wraps service method into async flow, with the ability to dispatching
  it to a preconfigured executor service."
  [{:keys [executors] :as cfg} f mdata]
  (let [dname (::async/dispatch mdata :default)]
    (if (= :none dname)
      (with-meta
        (fn [cfg params]
          (p/do (f cfg params)))
        mdata)

      (let [executor (get executors dname)]
        (when-not executor
          (ex/raise :type :internal
                    :code :executor-not-configured
                    :hint (format "executor %s not configured" dname)))
        (with-meta
          (fn [cfg params]
            (-> (px/submit! executor #(f cfg params))
                (p/bind p/wrap)))
          mdata)))))

(defn- wrap-audit
  [{:keys [audit] :as cfg} f mdata]
  (if audit
    (with-meta
      (fn [cfg {:keys [::request] :as params}]
        (p/finally (f cfg params)
                   (fn [result _]
                     (when result
                       (let [resultm    (meta result)
                             profile-id (or (::audit/profile-id resultm)
                                            (:profile-id result)
                                            (:profile-id params))
                             props      (or (::audit/replace-props resultm)
                                            (-> params
                                                (merge (::audit/props resultm))
                                                (dissoc :type)))]
                         (audit :cmd :submit
                                :type (or (::audit/type resultm)
                                          (::type cfg))
                                :name (or (::audit/name resultm)
                                          (::sv/name mdata))
                                :profile-id profile-id
                                :ip-addr (some-> request audit/parse-client-ip)
                                :props (dissoc props ::request)))))))
      mdata)
    f))

(defn- wrap
  [cfg f mdata]
  (let [f     (as-> f $
                (wrap-dispatch cfg $ mdata)
                (rlimit/wrap-rlimit cfg $ mdata)
                (retry/wrap-retry cfg $ mdata)
                (wrap-audit cfg $ mdata)
                (wrap-metrics cfg $ mdata)
                )

        spec  (or (::sv/spec mdata) (s/spec any?))
        auth? (:auth mdata true)]

    (l/trace :action "register" :name (::sv/name mdata))
    (with-meta
      (fn [{:keys [::request] :as params}]
        ;; Raise authentication error when rpc method requires auth but
        ;; no profile-id is found in the request.
        (p/do!
         (if (and auth? (not (uuid? (:profile-id params))))
           (ex/raise :type :authentication
                     :code :authentication-required
                     :hint "authentication required for this endpoint")
           (let [params (us/conform spec (dissoc params ::request))]
             (f cfg (assoc params ::request request))))))

      mdata)))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    ;; (prn mdata)
    [(keyword (::sv/name mdata))
     (wrap cfg vfn mdata)]))

(defn- resolve-query-methods
  [cfg]
  (let [cfg (assoc cfg ::type "query" ::metrics-id :rpc-query-timing)]
    (->> (sv/scan-ns 'app.rpc.queries.projects
                     'app.rpc.queries.files
                     'app.rpc.queries.teams
                     'app.rpc.queries.comments
                     'app.rpc.queries.profile
                     'app.rpc.queries.viewer
                     'app.rpc.queries.fonts)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-mutation-methods
  [cfg]
  (let [cfg (assoc cfg ::type "mutation" ::metrics-id :rpc-mutation-timing)]
    (->> (sv/scan-ns 'app.rpc.mutations.media
                     'app.rpc.mutations.profile
                     'app.rpc.mutations.files
                     'app.rpc.mutations.comments
                     'app.rpc.mutations.projects
                     'app.rpc.mutations.teams
                     'app.rpc.mutations.management
                     'app.rpc.mutations.fonts
                     'app.rpc.mutations.share-link
                     'app.rpc.mutations.verify-token)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-command-methods
  [cfg]
  (let [cfg (assoc cfg ::type "command" ::metrics-id :rpc-command-timing)]
    (->> (sv/scan-ns 'app.rpc.commands.binfile
                     'app.rpc.commands.comments
                     'app.rpc.commands.auth
                     'app.rpc.commands.ldap
                     'app.rpc.commands.demo)
         (map (partial process-method cfg))
         (into {}))))

(s/def ::audit (s/nilable fn?))
(s/def ::executors (s/map-of keyword? ::wrk/executor))
(s/def ::executors map?)
(s/def ::http-client fn?)
(s/def ::ldap (s/nilable map?))
(s/def ::msgbus fn?)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::storage some?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec ::methods [_]
  (s/keys :req-un [::storage
                   ::session
                   ::tokens
                   ::audit
                   ::executors
                   ::public-uri
                   ::msgbus
                   ::http-client
                   ::mtx/metrics
                   ::db/pool
                   ::ldap]))

(defmethod ig/init-key ::methods
  [_ cfg]
  {:mutations (resolve-mutation-methods cfg)
   :queries   (resolve-query-methods cfg)
   :commands  (resolve-command-methods cfg)})

(s/def ::mutations
  (s/map-of keyword? fn?))

(s/def ::queries
  (s/map-of keyword? fn?))

(s/def ::commands
  (s/map-of keyword? fn?))

(s/def ::methods
  (s/keys :req-un [::mutations
                   ::queries
                   ::commands]))

(defmethod ig/pre-init-spec ::routes [_]
  (s/keys :req-un [::methods]))

(defmethod ig/init-key ::routes
  [_ {:keys [methods] :as cfg}]
  [["/rpc"
    ["/command/:command" {:handler (partial rpc-command-handler (:commands methods))}]
    ["/query/:type" {:handler (partial rpc-query-handler (:queries methods))}]
    ["/mutation/:type" {:handler (partial rpc-mutation-handler (:mutations methods))
                        :allowed-methods #{:post}}]]])

