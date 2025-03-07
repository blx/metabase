(ns metabase.email.messages
  "Convenience functions for sending templated email messages.  Each function here should represent a single email.
   NOTE: we want to keep this about email formatting, so don't put heavy logic here RE: building data for emails."
  (:require [clojure.core.cache :as cache]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup.core :refer [html]]
            [java-time :as t]
            [medley.core :as m]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.util :as driver.u]
            [metabase.email :as email]
            [metabase.public-settings :as public-settings]
            [metabase.pulse.markdown :as markdown]
            [metabase.pulse.parameters :as params]
            [metabase.pulse.render :as render]
            [metabase.pulse.render.body :as render.body]
            [metabase.pulse.render.image-bundle :as image-bundle]
            [metabase.pulse.render.js-svg :as js-svg]
            [metabase.pulse.render.style :as render.style]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.streaming :as qp.streaming]
            [metabase.query-processor.streaming.interface :as qp.streaming.i]
            [metabase.query-processor.streaming.xlsx :as xlsx]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.i18n :as i18n :refer [deferred-trs trs tru]]
            [metabase.util.urls :as url]
            [stencil.core :as stencil]
            [stencil.loader :as stencil-loader]
            [toucan.db :as db])
  (:import [java.io File IOException OutputStream]
           java.time.format.DateTimeFormatter
           java.time.LocalTime))

(defn- app-name-trs
  "Return the user configured application name, or Metabase translated
  via tru if a name isn't configured."
  []
  (or (public-settings/application-name)
      (trs "Metabase")))

;; Dev only -- disable template caching
(when config/is-dev?
  (alter-meta! #'stencil.core/render-file assoc :style/indent 1)
  (stencil-loader/set-cache (cache/ttl-cache-factory {} :ttl 0)))

(def ^:private ^:const data-uri-svg-regex #"^data:image/svg\+xml;base64,(.*)$")

(defn- data-uri-svg? [url]
  (re-matches data-uri-svg-regex url))

(defn- themed-image-url
  [url color]
  (try
    (let [base64 (second (re-matches data-uri-svg-regex url))
          svg    (u/decode-base64 base64)
          themed (str/replace svg #"<svg\b([^>]*)( fill=\"[^\"]*\")([^>]*)>" (str "<svg$1$3 fill=\"" color "\">"))]
      (str "data:image/svg+xml;base64," (u/encode-base64 themed)))
    (catch Throwable e
      url)))

(defn- logo-url []
  (let [url   (public-settings/application-logo-url)
        color (render.style/primary-color)]
    (cond
      (= url "app/assets/img/logo.svg") "http://static.metabase.com/email_logo.png"
      ;; NOTE: disabling whitelabeled URLs for now since some email clients don't render them correctly
      ;; We need to extract them and embed as attachments like we do in metabase.pulse.render.image-bundle
      true                              nil
      (data-uri-svg? url)               (themed-image-url url color)
      :else                             url)))

(defn- icon-bundle
  [icon-name]
  (let [color     (render.style/primary-color)
        png-bytes (js-svg/icon icon-name color)]
    (-> (image-bundle/make-image-bundle :attachment png-bytes)
        (image-bundle/image-bundle->attachment))))

(defn- button-style [color]
  (str "display: inline-block; "
       "box-sizing: border-box; "
       "padding: 0.5rem 1.375rem; "
       "font-size: 1.063rem; "
       "font-weight: bold; "
       "text-decoration: none; "
       "cursor: pointer; "
       "color: #fff; "
       "border: 1px solid " color "; "
       "background-color: " color "; "
       "border-radius: 4px;"))

;;; Various Context Helper Fns. Used to build Stencil template context

(defn- common-context
  "Context that is used across multiple email templates, and that is the same for all emails"
  []
  {:applicationName           (public-settings/application-name)
   :applicationColor          (render.style/primary-color)
   :applicationLogoUrl        (logo-url)
   :buttonStyle               (button-style (render.style/primary-color))
   :colorTextLight            render.style/color-text-light
   :colorTextMedium           render.style/color-text-medium
   :colorTextDark             render.style/color-text-dark
   :notificationManagementUrl (url/notification-management-url)
   :siteUrl                   (public-settings/site-url)})

(def ^:private notification-context
  {:emailType  "notification"
   :logoHeader true})

(defn- abandonment-context []
  {:heading      (trs "We’d love your feedback.")
   :callToAction (str (deferred-trs "It looks like Metabase wasn’t quite a match for you.")
                      " "
                      (deferred-trs "Would you mind taking a fast 5 question survey to help the Metabase team understand why and make things better in the future?"))
   :link         "https://metabase.com/feedback/inactive"})

(defn- follow-up-context []
  {:heading      (trs "We hope you''ve been enjoying Metabase.")
   :callToAction (trs "Would you mind taking a fast 6 question survey to tell us how it’s going?")
   :link         "https://metabase.com/feedback/active"})


;;; ### Public Interface


(defn send-new-user-email!
  "Send an email to `invitied` letting them know `invitor` has invited them to join Metabase."
  [invited invitor join-url]
  (let [company      (or (public-settings/site-name) "Unknown")
        message-body (stencil/render-file "metabase/email/new_user_invite"
                                          (merge (common-context)
                                                 {:emailType    "new_user_invite"
                                                  :invitedName  (:first_name invited)
                                                  :invitorName  (:first_name invitor)
                                                  :invitorEmail (:email invitor)
                                                  :company      company
                                                  :joinUrl      join-url
                                                  :today        (t/format "MMM'&nbsp;'dd,'&nbsp;'yyyy" (t/zoned-date-time))
                                                  :logoHeader   true}))]
    (email/send-message!
     :subject      (str (trs "You''re invited to join {0}''s {1}" company (app-name-trs)))
     :recipients   [(:email invited)]
     :message-type :html
     :message      message-body)))

(defn- all-admin-recipients
  "Return a sequence of email addresses for all Admin users.

  The first recipient will be the site admin (or oldest admin if unset), which is the address that should be used in
  `mailto` links (e.g., for the new user to email with any questions)."
  []
  (concat (when-let [admin-email (public-settings/admin-email)]
            [admin-email])
          (db/select-field :email 'User, :is_superuser true, :is_active true, {:order-by [[:id :asc]]})))

(defn send-user-joined-admin-notification-email!
  "Send an email to the `invitor` (the Admin who invited `new-user`) letting them know `new-user` has joined."
  [new-user & {:keys [google-auth?]}]
  {:pre [(map? new-user)]}
  (let [recipients (all-admin-recipients)]
    (email/send-message!
     :subject      (str (if google-auth?
                          (trs "{0} created a {1} account" (:common_name new-user) (app-name-trs))
                          (trs "{0} accepted their {1} invite" (:common_name new-user) (app-name-trs))))
     :recipients   recipients
     :message-type :html
     :message      (stencil/render-file "metabase/email/user_joined_notification"
                                        (merge (common-context)
                                               {:logoHeader        true
                                                :joinedUserName    (:first_name new-user)
                                                :joinedViaSSO      google-auth?
                                                :joinedUserEmail   (:email new-user)
                                                :joinedDate        (t/format "EEEE, MMMM d" (t/zoned-date-time)) ; e.g. "Wednesday, July 13". TODO - is this what we want?
                                                :adminEmail        (first recipients)
                                                :joinedUserEditUrl (str (public-settings/site-url) "/admin/people")})))))

(defn send-password-reset-email!
  "Format and send an email informing the user how to reset their password."
  [email google-auth? hostname password-reset-url is-active?]
  {:pre [(m/boolean? google-auth?)
         (u/email? email)
         (string? hostname)
         (string? password-reset-url)]}
  (let [message-body (stencil/render-file
                      "metabase/email/password_reset"
                      (merge (common-context)
                             {:emailType        "password_reset"
                              :hostname         hostname
                              :sso              google-auth?
                              :passwordResetUrl password-reset-url
                              :logoHeader       true
                              :isActive         is-active?
                              :adminEmail       (public-settings/admin-email)
                              :adminEmailSet    (boolean (public-settings/admin-email))}))]
    (email/send-message!
     :subject      (trs "[{0}] Password Reset Request" (app-name-trs))
     :recipients   [email]
     :message-type :html
     :message      message-body)))

(defn send-login-from-new-device-email!
  "Format and send an email informing the user that this is the first time we've seen a login from this device. Expects
  login history infomation as returned by `metabase.models.login-history/human-friendly-infos`."
  [{user-id :user_id, :keys [timestamp], :as login-history}]
  (let [user-info    (db/select-one ['User [:first_name :first-name] :email :locale] :id user-id)
        user-locale  (or (:locale user-info) (i18n/site-locale))
        timestamp    (u.date/format-human-readable timestamp user-locale)
        context      (merge (common-context)
                            {:first-name (:first-name user-info)
                             :device     (:device_description login-history)
                             :location   (:location login-history)
                             :timestamp  timestamp})
        message-body (stencil/render-file "metabase/email/login_from_new_device"
                                          context)]
    (email/send-message!
     :subject      (trs "We''ve Noticed a New {0} Login, {1}" (app-name-trs) (:first-name user-info))
     :recipients   [(:email user-info)]
     :message-type :html
     :message      message-body)))

;; TODO - I didn't write these function and I don't know what it's for / what it's supposed to be doing. If this is
;; determined add appropriate documentation

(defn- model-name->url-fn [model]
  (case model
    "Card"      url/card-url
    "Dashboard" url/dashboard-url
    "Pulse"     url/pulse-url
    "Segment"   url/segment-url))

(defn- add-url-to-dependency [{:keys [id model], :as obj}]
  (assoc obj :url ((model-name->url-fn model) id)))

(defn- build-dependencies
  "Build a sequence of dependencies from a `model-name->dependencies` map, and add various information such as obj URLs."
  [model-name->dependencies]
  (for [model-name (sort (keys model-name->dependencies))
        :let       [user-facing-name (if (= model-name "Card")
                                       "Saved Question"
                                       model-name)]
        deps       (get model-name->dependencies model-name)]
    {:model   user-facing-name
     :objects (for [dep deps]
                (add-url-to-dependency dep))}))

(defn send-notification-email!
  "Format and send an email informing the user about changes to objects in the system."
  [email context]
  {:pre [(u/email? email) (map? context)]}
  (let [context      (merge (update context :dependencies build-dependencies)
                            notification-context)
        message-body (stencil/render-file "metabase/email/notification"
                                          (merge (common-context) context))]
    (email/send-message!
     :subject      (trs "[{0}] Notification" (app-name-trs))
     :recipients   [email]
     :message-type :html
     :message      message-body)))

(defn send-follow-up-email!
  "Format and send an email to the system admin following up on the installation."
  [email msg-type]
  {:pre [(u/email? email) (contains? #{"abandon" "follow-up"} msg-type)]}
  (let [subject      (str (if (= "abandon" msg-type)
                            (trs "[{0}] Help make [{1}] better." (app-name-trs) (app-name-trs))
                            (trs "[{0}] Tell us how things are going." (app-name-trs))))
        context      (merge notification-context
                            (if (= "abandon" msg-type)
                              (abandonment-context)
                              (follow-up-context)))
        message-body (stencil/render-file "metabase/email/follow_up_email"
                                          (merge (common-context) context))]
    (email/send-message!
     :subject      subject
     :recipients   [email]
     :message-type :html
     :message      message-body)))

(defn- make-message-attachment [[content-id url]]
  {:type         :inline
   :content-id   content-id
   :content-type "image/png"
   :content      url})

(defn- pulse-link-context
  [{:keys [cards dashboard_id]}]
  (when-let [dashboard-id (or dashboard_id
                              (some :dashboard_id cards))]
    {:pulseLink (url/dashboard-url dashboard-id)}))

(defn- pulse-context [pulse dashboard]
  (merge (common-context)
         {:emailType                 "pulse"
          :title                     (:name pulse)
          :titleUrl                  (params/dashboard-url (:id dashboard) (params/parameters pulse dashboard))
          :dashboardDescription      (:description dashboard)
          :creator                   (-> pulse :creator :common_name)
          :sectionStyle              (render.style/style (render.style/section-style))}
         (pulse-link-context pulse)))

(defn- create-temp-file
  "Separate from `create-temp-file-or-throw` primarily so that we can simulate exceptions in tests"
  [suffix]
  (doto (File/createTempFile "metabase_attachment" suffix)
    .deleteOnExit))

(defn- create-temp-file-or-throw
  "Tries to create a temp file, will give the users a better error message if we are unable to create the temp file"
  [suffix]
  (try
    (create-temp-file suffix)
    (catch IOException e
      (let [ex-msg (tru "Unable to create temp file in `{0}` for email attachments "
                        (System/getProperty "java.io.tmpdir"))]
        (throw (IOException. ex-msg e))))))

(defn- create-result-attachment-map [export-type card-name ^File attachment-file]
  (let [{:keys [content-type]} (qp.streaming.i/stream-options export-type)]
    {:type         :attachment
     :content-type content-type
     :file-name    (format "%s.%s" card-name (name export-type))
     :content      (-> attachment-file .toURI .toURL)
     :description  (format "More results for '%s'" card-name)}))

(defn- include-csv-attachment?
  "Should this `card` and `results` include a CSV attachment?"
  [{include-csv? :include_csv, include-xls? :include_xls, card-name :name, :as card} {:keys [cols rows], :as result-data}]
  (letfn [(yes [reason & args]
            (log/tracef "Including CSV attachement for Card %s because %s" (pr-str card-name) (apply format reason args))
            true)
          (no [reason & args]
            (log/tracef "NOT including CSV attachement for Card %s because %s" (pr-str card-name) (apply format reason args))
            false)]
    (cond
      include-csv?
      (yes "it has `:include_csv`")

      include-xls?
      (no "it has `:include_xls`")

      (some (complement render.body/show-in-table?) cols)
      (yes "some columns are not included in rendered results")

      (not= :table (render/detect-pulse-chart-type card result-data))
      (no "we've determined it should not be rendered as a table")

      (= (count (take render.body/cols-limit cols)) render.body/cols-limit)
      (yes "the results have >= %d columns" render.body/cols-limit)

      (= (count (take render.body/rows-limit rows)) render.body/rows-limit)
      (yes "the results have >= %d rows" render.body/rows-limit)

      :else
      (no "less than %d columns, %d rows in results" render.body/cols-limit render.body/rows-limit))))

(defn- stream-api-results-to-export-format
  "For legacy compatability. Takes QP results in the normal `:api` response format and streams them to a different
  format.

  TODO -- this function is provided mainly because rewriting all of the Pulse/Alert code to stream results directly
  was a lot of work. I intend to rework that code so we can stream directly to the correct export format(s) at some
  point in the future; for now, this function is a stopgap.

  Results are streamed synchronosuly. Caller is responsible for closing `os` when this call is complete."
  [export-format ^OutputStream os {{:keys [rows]} :data, database-id :database_id, :as results}]
  ;; make sure Database/driver info is available for the streaming results writers -- they might need this in order to
  ;; get timezone information when writing results
  (driver/with-driver (driver.u/database->driver database-id)
    (qp.store/with-store
      (qp.store/fetch-and-store-database! database-id)
      (binding [xlsx/*parse-temporal-string-values* true]
        (let [w                           (qp.streaming.i/streaming-results-writer export-format os)
              cols                        (-> results :data :cols)
              viz-settings                (-> results :data :viz-settings)
              [ordered-cols output-order] (qp.streaming/order-cols cols viz-settings)
              viz-settings'               (assoc viz-settings :output-order output-order)]
          (qp.streaming.i/begin! w
                                 (assoc-in results [:data :ordered-cols] ordered-cols)
                                 viz-settings')
          (dorun
           (map-indexed
            (fn [i row]
              (qp.streaming.i/write-row! w row i ordered-cols viz-settings'))
            rows))
          (qp.streaming.i/finish! w results))))))

(defn- result-attachment
  [{{card-name :name, :as card} :card, {{:keys [rows], :as result-data} :data, :as result} :result}]
  (when (seq rows)
    [(when-let [temp-file (and (include-csv-attachment? card result-data)
                               (create-temp-file-or-throw "csv"))]
       (with-open [os (io/output-stream temp-file)]
         (stream-api-results-to-export-format :csv os result))
       (create-result-attachment-map "csv" card-name temp-file))
     (when-let [temp-file (and (:include_xls card)
                               (create-temp-file-or-throw "xlsx"))]
       (with-open [os (io/output-stream temp-file)]
         (stream-api-results-to-export-format :xlsx os result))
       (create-result-attachment-map "xlsx" card-name temp-file))]))

(defn- result-attachments [results]
  (filter some? (mapcat result-attachment results)))

(defn- render-result-card
  [timezone result]
  (if (:card result)
    (render/render-pulse-section timezone result)
    {:content (markdown/process-markdown (:text result) :html)}))

(defn- render-filters
  [notification dashboard]
  (let [filters (params/parameters notification dashboard)
        cells   (map
                 (fn [filter]
                   [:td {:class "filter-cell"
                         :style (render.style/style {:width "50%"
                                                     :padding "0px"
                                                     :vertical-align "baseline"})}
                    [:table {:cellpadding "0"
                             :cellspacing "0"
                             :width "100%"
                             :height "100%"}
                     [:tr
                      [:td
                       {:style (render.style/style {:color render.style/color-text-medium
                                                    :min-width "100px"
                                                    :width "50%"
                                                    :padding "4px 4px 4px 0"
                                                    :vertical-align "baseline"})}
                       (:name filter)]
                      [:td
                       {:style (render.style/style {:color render.style/color-text-dark
                                                    :min-width "100px"
                                                    :width "50%"
                                                    :padding "4px 16px 4px 8px"
                                                    :vertical-align "baseline"})}
                       (params/value-string filter)]]]])
                 filters)
        rows    (partition 2 2 nil cells)]
    (html
     [:table {:style (render.style/style {:table-layout :fixed
                                          :border-collapse :collapse
                                          :cellpadding "0"
                                          :cellspacing "0"
                                          :width "100%"
                                          :font-size  "12px"
                                          :font-weight 700
                                          :margin-top "8px"})}
      (for [row rows]
        [:tr {} row])])))

(defn- render-message-body
  [notification message-type message-context timezone dashboard results]
  (let [rendered-cards  (binding [render/*include-title* true]
                          (mapv #(render-result-card timezone %) results))
        icon-name       (case message-type
                          :alert :bell
                          :pulse :dashboard)
        icon-attachment (first (map make-message-attachment (icon-bundle icon-name)))
        filters         (when dashboard
                          (render-filters notification dashboard))
        message-body    (assoc message-context :pulse (html (vec (cons :div (map :content rendered-cards))))
                               :filters filters
                               :iconCid (:content-id icon-attachment))
        attachments     (apply merge (map :attachments rendered-cards))]
    (vec (concat [{:type "text/html; charset=utf-8" :content (stencil/render-file "metabase/email/pulse" message-body)}]
                 (map make-message-attachment attachments)
                 [icon-attachment]
                 (result-attachments results)))))

(defn- assoc-attachment-booleans [pulse results]
  (for [{{result-card-id :id} :card :as result} results
        :let [pulse-card (m/find-first #(= (:id %) result-card-id) (:cards pulse))]]
    (if result-card-id
      (update result :card merge (select-keys pulse-card [:include_csv :include_xls]))
      result)))

(defn render-pulse-email
  "Take a pulse object and list of results, returns an array of attachment objects for an email"
  [timezone pulse dashboard results]
  (render-message-body pulse
                       :pulse
                       (pulse-context pulse dashboard)
                       timezone
                       dashboard
                       (assoc-attachment-booleans pulse results)))

(defn pulse->alert-condition-kwd
  "Given an `alert` return a keyword representing what kind of goal needs to be met."
  [{:keys [alert_above_goal alert_condition card creator] :as alert}]
  (if (= "goal" alert_condition)
    (if (true? alert_above_goal)
      :meets
      :below)
    :rows))

(defn- first-card
  "Alerts only have a single card, so the alerts API accepts a `:card` key, while pulses have `:cards`. Depending on
  whether the data comes from the alert API or pulse tasks, the card could be under `:card` or `:cards`"
  [alert]
  (or (:card alert)
      (first (:cards alert))))

(defn- common-alert-context
  "Template context that is applicable to all alert templates, including alert management templates
  (e.g. the subscribed/unsubscribed emails)"
  ([alert]
   (common-alert-context alert nil))
  ([alert alert-condition-map]
   (let [{card-id :id, card-name :name} (first-card alert)]
     (merge (common-context)
            {:emailType                 "alert"
             :questionName              card-name
             :questionURL               (url/card-url card-id)
             :sectionStyle              (render.style/section-style)}
            (when alert-condition-map
              {:alertCondition (get alert-condition-map (pulse->alert-condition-kwd alert))})))))

(defn- schedule-hour-text
  [{hour :schedule_hour}]
  (.format (LocalTime/of hour 0)
           (DateTimeFormatter/ofPattern "h a")))

(defn- schedule-day-text
  [{day :schedule_day}]
  (get {"sun" "Sunday"
        "mon" "Monday"
        "tue" "Tuesday"
        "wed" "Wednesday"
        "thu" "Thursday"
        "fri" "Friday"
        "sat" "Saturday"}
       day))

(defn- schedule-timezone
  []
  (or (driver/report-timezone) "UTC"))

(defn- alert-schedule-text
  "Returns a string that describes the run schedule of an alert (i.e. how often results are checked),
  for inclusion in the email template. Not translated, since emails in general are not currently translated."
  [channel]
  (case (:schedule_type channel)
    :hourly
    "Run hourly"

    :daily
    (format "Run daily at %s %s"
            (schedule-hour-text channel)
            (schedule-timezone))

    :weekly
    (format "Run weekly on %s at %s %s"
            (schedule-day-text channel)
            (schedule-hour-text channel)
            (schedule-timezone))))

(defn- alert-context
  "Context that is applicable only to the actual alert template (not alert management templates)"
  [alert channel]
  (let [{card-id :id, card-name :name} (first-card alert)]
    {:title         card-name
     :titleUrl      (url/card-url card-id)
     :alertSchedule (alert-schedule-text channel)
     :creator       (-> alert :creator :common_name)}))

(defn- alert-results-condition-text [goal-value]
  {:meets (format "This question has reached its goal of %s." goal-value)
   :below (format "This question has gone below its goal of %s." goal-value)})

(defn render-alert-email
  "Take a pulse object and list of results, returns an array of attachment objects for an email"
  [timezone {:keys [alert_first_only] :as alert} channel results goal-value]
  (let [message-ctx  (merge
                      (common-alert-context alert (alert-results-condition-text goal-value))
                      (alert-context alert channel))]
    (render-message-body alert
                         :alert
                         (assoc message-ctx :firstRunOnly? alert_first_only)
                         timezone
                         nil
                         (assoc-attachment-booleans alert results))))

(def ^:private alert-condition-text
  {:meets "when this question meets its goal"
   :below "when this question goes below its goal"
   :rows  "whenever this question has any results"})

(defn- send-email!
  "Sends an email on a background thread, returning a future."
  [user subject template-path template-context]
  (future
    (try
      (email/send-message-or-throw!
       {:recipients   [(:email user)]
        :message-type :html
        :subject      subject
        :message      (stencil/render-file template-path template-context)})
      (catch Exception e
        (log/errorf e "Failed to send message to '%s' with subject '%s'" (:email user) subject)))))

(defn- template-path [template-name]
  (str "metabase/email/" template-name ".mustache"))

;; Paths to the templates for all of the alerts emails
(def ^:private new-alert-template          (template-path "alert_new_confirmation"))
(def ^:private you-unsubscribed-template   (template-path "alert_unsubscribed"))
(def ^:private admin-unsubscribed-template (template-path "alert_admin_unsubscribed_you"))
(def ^:private added-template              (template-path "alert_you_were_added"))
(def ^:private stopped-template            (template-path "alert_stopped_working"))

(defn send-new-alert-email!
  "Send out the initial 'new alert' email to the `creator` of the alert"
  [{:keys [creator] :as alert}]
  (send-email! creator "You set up an alert" new-alert-template
               (common-alert-context alert alert-condition-text)))

(defn send-you-unsubscribed-alert-email!
  "Send an email to `who-unsubscribed` letting them know they've unsubscribed themselves from `alert`"
  [alert who-unsubscribed]
  (send-email! who-unsubscribed "You unsubscribed from an alert" you-unsubscribed-template
               (common-alert-context alert)))

(defn send-admin-unsubscribed-alert-email!
  "Send an email to `user-added` letting them know `admin` has unsubscribed them from `alert`"
  [alert user-added {:keys [first_name last_name] :as admin}]
  (let [admin-name (format "%s %s" first_name last_name)]
    (send-email! user-added "You’ve been unsubscribed from an alert" admin-unsubscribed-template
                 (assoc (common-alert-context alert) :adminName admin-name))))

(defn send-you-were-added-alert-email!
  "Send an email to `user-added` letting them know `admin-adder` has added them to `alert`"
  [alert user-added {:keys [first_name last_name] :as admin-adder}]
  (let [subject (format "%s %s added you to an alert" first_name last_name)]
    (send-email! user-added subject added-template (common-alert-context alert alert-condition-text))))

(def ^:private not-working-subject "One of your alerts has stopped working")

(defn send-alert-stopped-because-archived-email!
  "Email to notify users when a card associated to their alert has been archived"
  [alert user {:keys [first_name last_name] :as archiver}]
  (let [deletion-text (format "the question was archived by %s %s" first_name last_name)]
    (send-email! user not-working-subject stopped-template (assoc (common-alert-context alert) :deletionCause deletion-text))))

(defn send-alert-stopped-because-changed-email!
  "Email to notify users when a card associated to their alert changed in a way that invalidates their alert"
  [alert user {:keys [first_name last_name] :as archiver}]
  (let [edited-text (format "the question was edited by %s %s" first_name last_name)]
    (send-email! user not-working-subject stopped-template (assoc (common-alert-context alert) :deletionCause edited-text))))
