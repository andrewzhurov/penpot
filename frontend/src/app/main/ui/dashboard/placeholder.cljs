;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.placeholder
  (:require
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc empty-placeholder
  [{:keys [dragging? on-create-clicked project] :as props}]
  (cond
    (true? dragging?)
    [:div.grid-row.no-wrap
     [:div.grid-item]]

    :else
    [:div.grid-empty-placeholder
     [:button.create-new {:on-click (partial on-create-clicked project "dashboard:empty-folder-placeholder")}
      (tr "dashboard.new-file")]]))

(mf/defc loading-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/loader]
   [:div.text (tr "dashboard.loading-files")]])

