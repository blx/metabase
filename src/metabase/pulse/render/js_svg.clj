(ns metabase.pulse.render.js-svg
  "Functions to render charts as svg strings by using graal's js engine. A bundle is built by `yarn build-static-viz`
  which has charting library. This namespace has some wrapper functions to invoke those functions. Interop is very
  strange, as the jvm datastructures, not just serialized versions are used. This is why we have the `toJSArray` and
  `toJSMap` functions to turn Clojure's normal datastructures into js native structures."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [metabase.pulse.render.js-engine :as js])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           java.nio.charset.StandardCharsets
           [org.apache.batik.anim.dom SAXSVGDocumentFactory SVGOMDocument]
           [org.apache.batik.transcoder TranscoderInput TranscoderOutput]
           org.apache.batik.transcoder.image.PNGTranscoder
           org.graalvm.polyglot.Context
           [org.w3c.dom Element Node]))

;; the bundle path goes through webpack. Changes require a `yarn build-static-viz`
(def ^:private bundle-path
  "frontend_client/app/dist/lib-static-viz.bundle.js")

;; the interface file does not go through webpack. Feel free to quickly change as needed and then re-require this
;; namespace to redef the `context`.
(def ^:private interface-path
  "frontend_shared/static_viz_interface.js")

(defn- load-viz-bundle [^Context context]
  (doto context
    (js/load-resource bundle-path)
    (js/load-resource interface-path)))

(defn- static-viz-context
  "Load the static viz js bundle into a new graal js context."
  []
  (load-viz-bundle (js/context)))

(def ^:private ^Context context
  "Javascript context suitable for evaluating the charts. It has the chart bundle and the above `src-api` in its
  environment suitable for creating charts."
  ;; todo is this thread safe? Should we have a resource pool on top of this? Or create them fresh for each invocation
  (delay (static-viz-context)))

(defn- post-process
  "Mutate in place the elements of the svg document. Remove the fill=transparent attribute in favor of
  fill-opacity=0.0. Our svg image renderer only understands the latter. Mutation is unfortunately necessary as the
  underlying tree of nodes is inherently mutable"
  [^SVGOMDocument svg-document & post-fns]
  (loop [s [(.getDocumentElement svg-document)]]
    (when-let [^Node node (peek s)]
      (let [s' (let [nodelist (.getChildNodes node)
                     length   (.getLength nodelist)]
                 (apply conj (pop s)
                        ;; reverse the nodes for the stack so it goes down first child first
                        (map #(.item nodelist %) (reverse (range length)))))]
        (reduce (fn [node f] (f node)) node post-fns)
        (recur s'))))
  svg-document)

(defn- fix-fill
  "The batik svg renderer does not understand fill=\"transparent\" so we must change that to
  fill-opacity=\"0.0\". Previously was just doing a string replacement but now is a proper tree walk fix."
  [^Node node]
  (letfn [(element? [x] (instance? Element x))]
    (if (and (element? node)
             (.hasAttribute ^Element node "fill")
             (= (.getAttribute ^Element node "fill") "transparent"))
      (doto ^Element node
        (.removeAttribute "fill")
        (.setAttribute "fill-opacity" "0.0"))
      node)))

(defn- parse-svg-string [^String s]
  (let [s       (str/replace s #"<svg" "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        factory (SAXSVGDocumentFactory. "org.apache.xerces.parsers.SAXParser")]
    (with-open [is (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8))]
      (.createDocument factory "file:///fake.svg" is))))

(def ^:dynamic ^:private *svg-render-width*
  "Width to render svg images. Intentionally large to improve quality. Consumers should be aware and resize as
  needed. Email should include width tags; slack automatically resizes inline and provides a nice detail view when
  clicked."
  (float 1200))

(def ^:dynamic ^:private *svg-render-height*
  "Height to render svg images. If not bound, will preserve aspect ratio of original image."
  nil)

(defn- render-svg
  ^bytes [^SVGOMDocument svg-document]
  (with-open [os (ByteArrayOutputStream.)]
    (let [^SVGOMDocument fixed-svg-doc (post-process svg-document fix-fill)
          in                           (TranscoderInput. fixed-svg-doc)
          out                          (TranscoderOutput. os)
          transcoder                   (PNGTranscoder.)]
      (.addTranscodingHint transcoder PNGTranscoder/KEY_WIDTH *svg-render-width*)
      (when *svg-render-height*
        (.addTranscodingHint transcoder PNGTranscoder/KEY_HEIGHT *svg-render-height*))
      (.transcode transcoder in out))
    (.toByteArray os)))

(defn- svg-string->bytes [s]
  (-> s parse-svg-string render-svg))

(defn timelineseries-line
  "Clojure entrypoint to render a timeseries line char. Rows should be tuples of [datetime numeric-value]. Labels is a
  map of {:left \"left-label\" :botton \"bottom-label\"}. Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "timeseries_line" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn timelineseries-waterfall
  "Clojure entrypoint to render a timeseries waterfall chart. Rows should be tuples of [datetime numeric-value]. Labels is
  a map of {:left \"left-label\" :botton \"bottom-label\". Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "timeseries_waterfall" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn timelineseries-bar
  "Clojure entrypoint to render a timeseries bar char. Rows should be tuples of [datetime numeric-value]. Labels is a
  map of {:left \"left-label\" :botton \"bottom-label\"}. Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "timeseries_bar" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn categorical-line
  "Clojure entrypoint to render a categorical line chart. Rows should be tuples of [stringable numeric-value]. Labels is
  a map of {:left \"left-label\" :botton \"bottom-label\". Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "categorical_line" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn categorical-waterfall
  "Clojure entrypoint to render a categorical waterfall chart. Rows should be tuples of [stringable numeric-value]. Labels is
  a map of {:left \"left-label\" :botton \"bottom-label\". Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "categorical_waterfall" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn categorical-bar
  "Clojure entrypoint to render a categorical bar chart. Rows should be tuples of [stringable numeric-value]. Labels is
  a map of {:left \"left-label\" :botton \"bottom-label\". Returns a byte array of a png file."
  [rows labels settings]
  (let [svg-string (.asString (js/execute-fn-name @context "categorical_bar" rows
                                                  (map (fn [[k v]] [(name k) v]) labels)
                                                  (json/generate-string settings)))]
    (svg-string->bytes svg-string)))

(defn categorical-donut
  "Clojure entrypoint to render a categorical donut chart. Rows should be tuples of [category numeric-value]. Returns a
  byte array of a png file"
  [rows colors]
  (let [svg-string (.asString (js/execute-fn-name @context "categorical_donut" rows (seq colors)))]
    (svg-string->bytes svg-string)))

(defn progress
  "Clojure entrypoint to render a progress bar. Returns a byte array of a png file"
  [value goal settings]
  (let [js-res (js/execute-fn-name @context "progress"
                                   (json/generate-string {:value value :goal goal})
                                   (json/generate-string settings))
        svg-string (.asString js-res)]
    (svg-string->bytes svg-string)))

(def ^:private icon-paths
  {:dashboard "M32 28a4 4 0 0 1-4 4H4a4.002 4.002 0 0 1-3.874-3H0V4a4 4 0 0 1 4-4h25a3 3 0 0 1 3 3v25zm-4 0V8H4v20h24zM7.273 18.91h10.182v4.363H7.273v-4.364zm0-6.82h17.454v4.365H7.273V12.09zm13.09 6.82h4.364v4.363h-4.363v-4.364z"
   :bell      "M14.254 5.105c-7.422.874-8.136 7.388-8.136 11.12 0 4.007 0 5.61-.824 6.411-.549.535-1.647.802-3.294.802v4.006h28v-4.006c-1.647 0-2.47 0-3.294-.802-.55-.534-.824-3.205-.824-8.013-.493-5.763-3.205-8.936-8.136-9.518a2.365 2.365 0 0 0 .725-1.701C18.47 2.076 17.364 1 16 1s-2.47 1.076-2.47 2.404c0 .664.276 1.266.724 1.7zM11.849 29c.383 1.556 1.793 2.333 4.229 2.333s3.845-.777 4.229-2.333h-8.458z"})

(defn- icon-svg-string
  [icon-name color]
  (str "<svg><path d=\"" (get icon-paths icon-name) "\" fill=\"" color "\"/></svg>"))

(defn icon
  "Entrypoint for rendering an SVG icon as a PNG, with a specific color"
  [icon-name color]
  (let [svg-string (icon-svg-string icon-name color)]
    (binding [*svg-render-width*  (float 33)
              *svg-render-height* (float 33)]
      (svg-string->bytes svg-string))))
