(ns metabase.driver.oracle-test
  "Tests for specific behavior of the Oracle driver."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [honeysql.core :as hsql]
            [metabase.api.common :as api]
            [metabase.driver :as driver]
            [metabase.driver.oracle :as oracle]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.util :as driver.u]
            [metabase.models.database :refer [Database]]
            [metabase.models.field :refer [Field]]
            [metabase.models.table :refer [Table]]
            [metabase.query-processor :as qp]
            [metabase.query-processor-test :as qp.test]
            [metabase.query-processor-test.order-by-test :as qp-test.order-by-test] ; used for one SSL connectivity test
            [metabase.sync :as sync]
            [metabase.test :as mt]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.oracle :as oracle.tx]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.test.util :as tu]
            [metabase.test.util.log :as tu.log]
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

(deftest connection-details->spec-test
  (doseq [[^String message expected-spec details]
          [["You should be able to connect with an SID"
            {:classname   "oracle.jdbc.OracleDriver"
             :subprotocol "oracle:thin"
             :subname     "@localhost:1521:ORCL"}
            {:host "localhost"
             :port 1521
             :sid  "ORCL"}]
           ["You should be able to specify a Service Name with no SID"
            {:classname   "oracle.jdbc.OracleDriver"
             :subprotocol "oracle:thin"
             :subname     "@localhost:1521/MyCoolService"}
            {:host         "localhost"
             :port         1521
             :service-name "MyCoolService"}]
           ["You should be able to specify a Service Name *and* an SID"
            {:classname   "oracle.jdbc.OracleDriver"
             :subprotocol "oracle:thin"
             :subname     "@localhost:1521:ORCL/MyCoolService"}
            {:host         "localhost"
             :port         1521
             :service-name "MyCoolService"
             :sid          "ORCL"}]
           ["You should be to specify SSL with a trust store"
            {:classname                         "oracle.jdbc.OracleDriver"
             :subprotocol                       "oracle:thin"
             :subname                           (str "@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)(HOST=localhost)(PORT=1521)"
                                                     ")(CONNECT_DATA=(SID=ORCL)(SERVICE_NAME=MyCoolService)))")}
            {:host                    "localhost"
             :port                    1521
             :service-name            "MyCoolService"
             :sid                     "ORCL"
             :ssl                     true}]]]
    (let [actual-spec (sql-jdbc.conn/connection-details->spec :oracle details)
          prog-prop   (deref #'oracle/prog-name-property)]
      (is (= (dissoc expected-spec prog-prop)
             (dissoc actual-spec prog-prop))
          message)
      ;; check our truncated Oracle version of the version/UUID string
      ;; in some test cases, the version info isn't set, to the string "null" is the value
      (is (re-matches #"MB (?:null|v(?:.*)) [\-a-f0-9]*" (get actual-spec prog-prop))))))

(deftest require-sid-or-service-name-test
  (testing "no SID and no Service Name should throw an exception"
    (is (thrown?
         AssertionError
         (sql-jdbc.conn/connection-details->spec :oracle {:host "localhost"
                                                          :port 1521})))
    (is (= "You must specify the SID and/or the Service Name."
           (try (sql-jdbc.conn/connection-details->spec :oracle {:host "localhost"
                                                                 :port 1521})
                (catch Throwable e
                  (driver/humanize-connection-error-message :oracle (.getMessage e))))))))

(deftest connection-properties-test
  (testing "Connection properties should be returned properly (including transformation of secret types)"
    (let [expected [{:name "host"}
                    {:name "port"}
                    {:name "sid"}
                    {:name "service-name"}
                    {:name "user"}
                    {:name "password"}
                    {:name "ssl"}
                    {:name "ssl-use-keystore"}
                    {:name       "ssl-keystore-options"
                     :type       "select"
                     :options    [{:name  "Local file path"
                                   :value "local"}
                                  {:name  "Uploaded file path"
                                   :value "uploaded"}]
                     :visible-if {:ssl-use-keystore true}}
                    {:name       "ssl-keystore-value"
                     :type       "textFile"
                     :visible-if {:ssl-keystore-options "uploaded"}}
                    {:name       "ssl-keystore-path"
                     :type       "string"
                     :visible-if {:ssl-keystore-options "local"}}
                    {:name "ssl-keystore-password-value"
                     :type "password"}
                    {:name "ssl-use-truststore"}
                    {:name       "ssl-truststore-options"
                     :type       "select"
                     :options    [{:name  "Local file path"
                                   :value "local"}
                                  {:name  "Uploaded file path"
                                   :value "uploaded"}]
                     :visible-if {:ssl-use-truststore true}}
                    {:name       "ssl-truststore-value"
                     :type       "textFile"
                     :visible-if {:ssl-truststore-options "uploaded"}}
                    {:name       "ssl-truststore-path"
                     :type       "string"
                     :visible-if {:ssl-truststore-options "local"}}
                    {:name "ssl-truststore-password-value"
                     :type "password"}
                    {:name "tunnel-enabled"}
                    {:name "tunnel-host"}
                    {:name "tunnel-port"}
                    {:name "tunnel-user"}
                    {:name "tunnel-auth-option"}
                    {:name "tunnel-pass"}
                    {:name "tunnel-private-key"}
                    {:name "tunnel-private-key-passphrase"}]
          actual   (-> (driver/connection-properties :oracle)
                       driver.u/connection-props-server->client)]
      (is (= expected (mt/select-keys-sequentially expected actual))))))

(deftest test-ssh-connection
  (testing "Gets an error when it can't connect to oracle via ssh tunnel"
    (mt/test-driver :oracle
      (is (thrown?
           java.net.ConnectException
           (try
             (let [engine :oracle
                   details {:ssl            false
                            :password       "changeme"
                            :tunnel-host    "localhost"
                            :tunnel-pass    "BOGUS-BOGUS"
                            :port           5432
                            :dbname         "test"
                            :host           "localhost"
                            :tunnel-enabled true
                            ;; we want to use a bogus port here on purpose -
                            ;; so that locally, it gets a ConnectionRefused,
                            ;; and in CI it does too. Apache's SSHD library
                            ;; doesn't wrap every exception in an SshdException
                            :tunnel-port    21212
                            :tunnel-user    "bogus"}]
               (tu.log/suppress-output
                (driver.u/can-connect-with-details? engine details :throw-exceptions)))
             (catch Throwable e
               (loop [^Throwable e e]
                 (or (when (instance? java.net.ConnectException e)
                       (throw e))
                     (some-> (.getCause e) recur))))))))))

(deftest timezone-id-test
  (mt/test-driver :oracle
    (is (= "UTC"
           (tu/db-timezone-id)))))

(deftest insert-rows-ddl-test
  (mt/test-driver :oracle
    (testing "Make sure we're generating correct DDL for Oracle to insert all rows at once."
      (is (= [[(str "INSERT ALL"
                    " INTO \"my_db\".\"my_table\" (\"col1\", \"col2\") VALUES (?, 1)"
                    " INTO \"my_db\".\"my_table\" (\"col1\", \"col2\") VALUES (?, 2) "
                    "SELECT * FROM dual")
               "A"
               "B"]]
             (ddl/insert-rows-ddl-statements :oracle (hx/identifier :table "my_db" "my_table") [{:col1 "A", :col2 1}
                                                                                                {:col1 "B", :col2 2}]))))))

(defn- do-with-temp-user [username f]
  (let [username (or username (tu/random-name))]
    (try
      (oracle.tx/create-user! username)
      (f username)
      (finally
        (oracle.tx/drop-user! username)))))

(defmacro ^:private with-temp-user
  "Run `body` with a temporary user bound, binding their name to `username-binding`. Use this to create the equivalent
  of temporary one-off databases. A particular username can be passed in as the binding or else one is generated with
  `tu/random-name`."
  [[username-binding & [username]] & body]
  `(do-with-temp-user ~username (fn [~username-binding] ~@body)))


(deftest return-clobs-as-text-test
  (mt/test-driver :oracle
    (testing "Make sure Oracle CLOBs are returned as text (#9026)"
      (let [details  (:details (mt/db))
            spec     (sql-jdbc.conn/connection-details->spec :oracle details)
            execute! (fn [format-string & args]
                       (jdbc/execute! spec (apply format format-string args)))
            pk-type  (sql.tx/pk-sql-type :oracle)]
        (with-temp-user [username]
          (execute! "CREATE TABLE \"%s\".\"messages\" (\"id\" %s, \"message\" CLOB)"            username pk-type)
          (execute! "INSERT INTO \"%s\".\"messages\" (\"id\", \"message\") VALUES (1, 'Hello')" username)
          (execute! "INSERT INTO \"%s\".\"messages\" (\"id\", \"message\") VALUES (2, NULL)"    username)
          (tt/with-temp* [Table [table    {:schema username, :name "messages", :db_id (mt/id)}]
                          Field [id-field {:table_id (u/the-id table), :name "id", :base_type "type/Integer"}]
                          Field [_        {:table_id (u/the-id table), :name "message", :base_type "type/Text"}]]
            (is (= [[1M "Hello"]
                    [2M nil]]
                   (qp.test/rows
                     (qp/process-query
                      {:database (mt/id)
                       :type     :query
                       :query    {:source-table (u/the-id table)
                                  :order-by     [[:asc [:field (u/the-id id-field) nil]]]}}))))))))))

(deftest handle-slashes-test
  (mt/test-driver :oracle
    (let [details  (:details (mt/db))
          spec     (sql-jdbc.conn/connection-details->spec :oracle details)
          execute! (fn [format-string & args]
                     (jdbc/execute! spec (apply format format-string args)))
          pk-type  (sql.tx/pk-sql-type :oracle)
          schema   (str (tu/random-name) "/")]
      (with-temp-user [username schema]
        (execute! "CREATE TABLE \"%s\".\"mess/ages/\" (\"id\" %s, \"column1\" varchar(200))" username pk-type)
        (testing "Sync can handle slashes in the schema and tablenames"
          (is (= #{"id" "column1"}
                 (into #{}
                       (map :name)
                       (:fields
                        (sql-jdbc.sync/describe-table :oracle spec {:name "mess/ages/" :schema username}))))))))))

;; let's make sure we're actually attempting to generate the correctl HoneySQL for joins and source queries so we
;; don't sit around scratching our heads wondering why the queries themselves aren't working
(deftest honeysql-test
  (mt/test-driver :oracle
    (testing "Correct HoneySQL form should be generated"
      (mt/with-everything-store
        (is (= (letfn [(id
                         ([field-name database-type]
                          (id oracle.tx/session-schema "test_data_venues" field-name database-type))
                         ([table-name field-name database-type]
                          (id nil table-name field-name database-type))
                         ([schema-name table-name field-name database-type]
                          (-> (hx/identifier :field schema-name table-name field-name)
                              (hx/with-database-type-info database-type))))]
                 {:select [:*]
                  :from   [{:select
                            [[(id "id" "number")
                              (hx/identifier :field-alias "id")]
                             [(id "name" "varchar2")
                              (hx/identifier :field-alias "name")]
                             [(id "category_id" "number")
                              (hx/identifier :field-alias "category_id")]
                             [(id "latitude" "binary_float")
                              (hx/identifier :field-alias "latitude")]
                             [(id "longitude" "binary_float")
                              (hx/identifier :field-alias "longitude")]
                             [(id "price" "number")
                              (hx/identifier :field-alias "price")]]
                            :from      [(hx/identifier :table oracle.tx/session-schema "test_data_venues")]
                            :left-join [[(hx/identifier :table oracle.tx/session-schema "test_data_categories")
                                         (hx/identifier :table-alias "test_data_categories__via__cat")]
                                        [:=
                                         (id "category_id" "number")
                                         (id "test_data_categories__via__cat" "id" "number")]]
                            :where     [:=
                                        (id "test_data_categories__via__cat" "name" "varchar2")
                                        "BBQ"]
                            :order-by  [[(id "id" "number") :asc]]}]
                  :where  [:<= (hsql/raw "rownum") 100]})
               (#'sql.qp/mbql->honeysql
                :oracle
                (qp/query->preprocessed
                 (mt/mbql-query venues
                   {:source-table $$venues
                    :order-by     [[:asc $id]]
                    :filter       [:=
                                   &test_data_categories__via__cat.categories.name
                                   [:value "BBQ" {:base_type :type/Text, :semantic_type :type/Name, :database_type "VARCHAR"}]]
                    :fields       [$id $name $category_id $latitude $longitude $price]
                    :limit        100
                    :joins        [{:source-table $$categories
                                    :alias        "test_data_categories__via__cat"
                                    :strategy     :left-join
                                    :condition    [:=
                                                   $category_id
                                                   &test_data_categories__via__cat.categories.id]
                                    :fk-field-id  (mt/id :venues :category_id)
                                    :fields       :none}]})))))))))

(deftest oracle-connect-with-ssl-test
  ;; ridiculously hacky test; hopefully it can be simplified; see inline comments for full explanations
  (mt/test-driver :oracle
    (if (System/getenv "MB_ORACLE_SSL_TEST_SSL") ; only even run this test if this env var is set
      ;; swap out :oracle env vars with any :oracle-ssl ones that were defined
      (mt/with-env-keys-renamed-by #(str/replace-first % "mb-oracle-ssl-test" "mb-oracle-test")
        ;; need to get a fresh instance of details to pick up env key changes
        (let [details      (->> (#'oracle.tx/connection-details)
                                (driver.u/db-details-client->server :oracle))
              orig-user-id api/*current-user-id*]
          (testing "Oracle can-connect? with SSL connection"
            (is (driver/can-connect? :oracle details)))
          (testing "Sync works with SSL connection"
            (binding [metabase.sync.util/*log-exceptions-and-continue?* false
                      api/*current-user-id* (mt/user->id :crowberto)]
              (mt/with-temp Database [database {:engine :oracle,
                                                :name (format "SSL connection version of %d" (mt/id)),
                                                :details details}]
                (mt/with-db database
                  (sync/sync-database! database {:scan :schema})
                  ;; should be four tables from test-data
                  (is (= 4 (db/count Table :db_id (u/the-id database) :name [:like "test_data%"])))
                  (binding [api/*current-user-id* orig-user-id ; restore original user-id to avoid perm errors
                            ;; we also need to rebind this dynamic var so that we can pretend "test-data" is
                            ;; actually the name of the database, and not some variation on the :name specified
                            ;; above, so that the table names resolve correctly in the generated query we can't
                            ;; simply call this new temp database "test-data", because then it will no longer be
                            ;; unique compared to the "real" "test-data" DB associated with the non-SSL (default)
                            ;; database, and the logic within metabase.test.data.interface/metabase-instance would
                            ;; be wrong (since we would end up with two :oracle Databases both named "test-data",
                            ;; violating its assumptions, in case the app DB ends up in an inconsistent state)
                            tx/*database-name-override* "test-data"]
                    (testing "Execute query with SSL connection"
                      (qp-test.order-by-test/order-by-test)))))))))
      (println (u/format-color 'yellow
                               "Skipping %s because %s env var is not set"
                               "oracle-connect-with-ssl-test"
                               "MB_ORACLE_SSL_TEST_SSL")))))

(deftest text-equals-empty-string-test
  (mt/test-driver :oracle
    (testing ":= with empty string should work correctly (#13158)"
      (mt/dataset airports
        (is (= [1M]
               (mt/first-row
                (mt/run-mbql-query airport {:aggregation [:count], :filter [:= $code ""]}))))))))
