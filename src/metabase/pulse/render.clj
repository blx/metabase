(ns metabase.pulse.render
  (:require [clojure.tools.logging :as log]
            [hiccup.core :refer [h]]
            [metabase.pulse.render.body :as body]
            [metabase.pulse.render.common :as common]
            [metabase.pulse.render.image-bundle :as image-bundle]
            [metabase.pulse.render.png :as png]
            [metabase.pulse.render.style :as style]
            [metabase.util.i18n :refer [trs tru]]
            [metabase.util.urls :as urls]
            [schema.core :as s]))

(def ^:dynamic *include-buttons*
  "Should the rendered pulse include buttons? (default: `false`)"
  false)

(def ^:dynamic *include-title*
  "Should the rendered pulse include a title? (default: `false`)"
  false)

(def ^:dynamic *include-description*
  "Should the rendered pulse include a card description? (default: `false`)"
  false)

(s/defn ^:private make-title-if-needed :- (s/maybe common/RenderedPulseCard)
  [render-type card dashcard]
  (when *include-title*
    (let [card-name    (or (-> dashcard :visualization_settings :card.title)
                           (-> card :name))
          image-bundle (when *include-buttons*
                         (image-bundle/external-link-image-bundle render-type))]
      {:attachments (when image-bundle
                      (image-bundle/image-bundle->attachment image-bundle))
       :content     [:table {:style (style/style {:margin-bottom   :8px
                                                  :border-collapse :collapse
                                                  :width           :100%})}
                     [:tbody
                      [:tr
                       [:td {:style (style/style {:padding :0
                                                  :margin  :0})}
                        [:span {:style (style/style (style/header-style))}
                         (h card-name)]]
                       [:td {:style (style/style {:text-align :right})}
                        (when *include-buttons*
                          [:img {:style (style/style {:width :16px})
                                 :width 16
                                 :src   (:image-src image-bundle)}])]]]]})))

(s/defn ^:private make-description-if-needed :- (s/maybe common/RenderedPulseCard)
  [dashcard]
  (when *include-description*
    (when-let [description (-> dashcard :visualization_settings :card.description)]
      {:attachments {}
       :content [:div {:style (style/style {:color style/color-text-medium
                                            :font-size :12px
                                            :margin-bottom :8px})}
                 description]})))

(defn- number-field?
  [{base-type :base_type, semantic-type :semantic_type}]
  (some #(isa? % :type/Number) [base-type semantic-type]))

(defn detect-pulse-chart-type
  "Determine the pulse (visualization) type of a `card`, e.g. `:scalar` or `:bar`."
  [{display-type :display, card-name :name, :as card} {:keys [cols rows insights], :as data}]
  (let [col-sample-count          (delay (count (take 3 cols)))
        row-sample-count          (delay (count (take 2 rows)))
        [col-1-rowfn col-2-rowfn] (common/graphing-column-row-fns card data)
        col-1                     (delay (col-1-rowfn cols))
        col-2                     (delay (col-2-rowfn cols))]
    (letfn [(chart-type [tyype reason & args]
              (log/tracef "Detected chart type %s for Card %s because %s"
                          tyype (pr-str card-name) (apply format reason args))
              tyype)
            (col-description [{col-name :name, base-type :base_type}]
              (format "%s (%s)" (pr-str col-name) base-type))]
      (cond
        (or (empty? rows)
            ;; Many aggregations result in [[nil]] if there are no rows to aggregate after filters
            (= [[nil]] (-> data :rows)))
        (chart-type :empty "there are no rows in results")

        (#{:pin_map :state :country} display-type)
        (chart-type nil "display-type is %s" display-type)

        (#{:progress :waterfall} display-type)
        (chart-type display-type "display-type is %s" display-type)

        (= @col-sample-count @row-sample-count 1)
        (chart-type :scalar "result has one row and one column")

        (and (= display-type :smartscalar)
             (= @col-sample-count 2)
             (seq insights))
        (chart-type :smartscalar "result has two columns and insights")

        (and (= @col-sample-count 2)
             (number-field? @col-2)
             (= display-type :bar))
        (chart-type :bar "result has two cols (%s and %s (number))" (col-description @col-1) (col-description @col-2))

        (and (= @col-sample-count 2)
             (> @row-sample-count 1)
             (number-field? @col-2)
             (not (#{:waterfall :pie :table} display-type)))
        (chart-type :sparkline "result has 2 cols (%s and %s (number)) and > 1 row" (col-description @col-1) (col-description @col-2))

        (and (= @col-sample-count 2)
             (number-field? @col-2)
             (= display-type :pie))
        (chart-type :categorical/donut "result has two cols (%s and %s (number))" (col-description @col-1) (col-description @col-2))

        :else
        (chart-type :table "no other chart types match")))))

(defn- is-attached?
  [card]
  ((some-fn :include_csv :include_xls) card))

(s/defn ^:private render-pulse-card-body :- common/RenderedPulseCard
  [render-type timezone-id :- (s/maybe s/Str) card {:keys [data error], :as results}]
  (try
    (when error
      (throw (ex-info (tru "Card has errors: {0}" error) results)))
    (let [chart-type (or (detect-pulse-chart-type card data)
                         (when (is-attached? card)
                           :attached)
                         :unknown)]
      (log/debug (trs "Rendering pulse card with chart-type {0} and render-type {1}" chart-type render-type))
      (body/render chart-type render-type timezone-id card data))
    (catch Throwable e
      (log/error e (trs "Pulse card render error"))
      (body/render :error nil nil nil nil))))

(defn- card-href
  [card]
  (h (urls/card-url (:id card))))

(s/defn render-pulse-card :- common/RenderedPulseCard
  "Render a single `card` for a `Pulse` to Hiccup HTML. `result` is the QP results. Returns a map with keys

- attachments
- content (a hiccup form suitable for rending on rich clients or rendering into an image)
- render/text : raw text suitable for substituting on clients when text is preferable. (Currently slack uses this for
  scalar results where text is preferable to an image of a div of a single result."
  [render-type timezone-id :- (s/maybe s/Str) card dashcard results]
  (let [{title :content, title-attachments :attachments} (make-title-if-needed render-type card dashcard)
        {description :content}                           (make-description-if-needed dashcard)
        {pulse-body       :content
         body-attachments :attachments
         text             :render/text}                  (render-pulse-card-body render-type timezone-id card results)]
    (cond-> {:attachments (merge title-attachments body-attachments)
             :content [:p
                       ;; Provide a horizontal scrollbar for tables that overflow container width.
                       ;; Surrounding <p> element prevents buggy behavior when dragging scrollbar.
                       [:div {:style (style/style {:overflow-x :auto})}
                        [:a {:href        (card-href card)
                             :target      "_blank"
                             :rel         "noopener noreferrer"
                             :style       (style/style
                                           (style/section-style)
                                           {:display         :block
                                            :text-decoration :none})}
                         title
                         description
                         [:div {:class "pulse-body"
                                :style (style/style {:display :block
                                                     :margin  :16px})}
                          pulse-body]]]]}
      text (assoc :render/text text))))

(defn render-pulse-card-for-display
  "Same as `render-pulse-card` but isn't intended for an email, rather for previewing so there is no need for
  attachments"
  [timezone-id card results]
  (:content (render-pulse-card :inline timezone-id card nil results)))

(s/defn render-pulse-section :- common/RenderedPulseCard
  "Render a single Card section of a Pulse to a Hiccup form (representating HTML)."
  [timezone-id {card :card, dashcard :dashcard, result :result}]
  (let [{:keys [attachments content]} (binding [*include-title*       true
                                                *include-description* true]
                                        (render-pulse-card :attachment timezone-id card dashcard result))]
    {:attachments attachments
     :content     [:div {:style (style/style {:margin-top    :20px
                                              :margin-bottom :20px})}
                   content]}))

(s/defn render-pulse-card-to-png :- bytes
  "Render a `pulse-card` as a PNG. `data` is the `:data` from a QP result (I think...)"
  [timezone-id :- (s/maybe s/Str) pulse-card result width]
  (png/render-html-to-png (render-pulse-card :inline timezone-id pulse-card nil result) width))

(s/defn png-from-render-info :- bytes
  "Create a PNG file (as a byte array) from rendering info."
  [rendered-info :- common/RenderedPulseCard width]
  (png/render-html-to-png rendered-info width))
