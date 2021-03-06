;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.build-config
  (:require [com.datastax.configbuilder.definitions :as d]
            [slingshot.slingshot :refer [throw+]]
            [lcm.utils.data :as data]
            [clojure.java.io :as io]
            [lcm.utils.version :as v]))

;; Configuration values that represent filesystem paths, such as the
;; Cassandra data directory. This record holds metadata about these
;; locations that may be needed downstream.
(defrecord ConfiguredPath [config-file
                           key
                           path
                           custom?    ;; is this different from the default?
                           directory? ;; is this a directory or file path?
                           ])

;; The input config-data will contain some model-info. These contain
;; data that is not defined in the definitions, such as listen_address.
(def model-info-keys #{:cluster-info :datacenter-info :node-info})
(defrecord ClusterInfo [name seeds])
(defrecord DatacenterInfo [name
                           graph-enabled
                           solr-enabled
                           spark-enabled])
;; Note, we are using the current _address field names as of DSE 6.0.
;; Namely native_transport_address and native_transport_rpc_address.
;; Clients should not be passing in the old names.
(defrecord NodeInfo [name
                     rack
                     listen_address
                     broadcast_address
                     native_transport_address
                     native_transport_broadcast_address
                     initial_token
                     auto_bootstrap
                     agent_version
                     configured-paths
                     facts])



(defn valid-config-keys?
  "Are the keys in config-data valid for this version of DSE?"
  [{:keys [definitions product datastax-version] :or {product "dse"}}
   config-data]
  (let [valid-key? (into (conj model-info-keys :address-yaml)
                         (keys definitions))
        invalid-keys (remove valid-key? (keys config-data))]
    (if (seq invalid-keys)
      (throw+ {:type :InvalidConfigKeys
               :message (format "Invalid config keys for DSE version %s: %s"
                                datastax-version
                                (data/format-seq invalid-keys))})
      config-data)))

(def tarball? #{"tarball"})
(def package? #{"package"})

(defn tarball-config?
  "Does this config represent a tarball installation?"
  [config-data]
  (tarball? (get-in config-data [:install-options :install-type])))

(defn get-install-directory
  "If the :install-directory from :install-options is non-empty, returns it.
  Otherwise, it gets the :install-directory from :node-info, which was posted
  as a fact by meld."
  [{:keys [node-info install-options] :as config-data}]
  (get (if (seq (:install-directory install-options))
         install-options
         (get-in node-info [:facts :run-context :install-options]))
       :install-directory))

(defn maybe-use-tarball-defaults
  "Returns definitions with the default-values potentially swapped out
  for tarball-defaults in the case of a tarball install-type."
  [{:keys [definitions] :as definitions-data} config-data]
  (if (tarball-config? config-data)
    (update definitions-data :definitions d/use-tarball-defaults)
    definitions-data))

(defn with-defaults
  "Fills in defaults from definitions where there is no user value.
   This will also add in missing config-data keys (for example, if
   :cassandra-env-sh is missing, it will be created with all default
   values)."
  [{:keys [definitions]} config-data]
  (reduce
   (fn [config-data [config-key config-definitions]]
     (update config-data config-key
             d/fill-in-defaults config-definitions))
   config-data
   definitions))

(defn get-management-field-id
  "Get the keyword of the management field for this config-file-id."
  [config-file-id]
  (keyword (str "lcm-manage--" (name config-file-id))))

(defn remove-unmanaged-config-files!
  "Remove config-ids that the user has decided not to manage.
   Each config file can be removed by a field of the following format:

   lcm-manage--(config file id)

   If a field with that naming convention exists and is false,
   then the config file corresponding to the id will not be rendered."
  [config-data]
  (let [all-config-files (keys config-data)]
    (reduce
      (fn [current-config-data [config-key config-definitions]]
        (let [management-field (get-management-field-id config-key)]
           (if (and (contains? (get config-data config-key) management-field)
                    (= false (get-in config-data [config-key management-field])))
             (dissoc current-config-data config-key)
             current-config-data)))
      config-data
      config-data)))

(defmulti enrich-config
          "Enriches the config-data for a given config-key with data from the
          model-info attributes."
          (fn [_ config-key _] config-key))

(defmethod enrich-config :default
  [_ _ config-data]
  ;; default behavior is to do no enrichment
  config-data)

(def ^:private pre-dse-60-field-mappings {:native_transport_address           :rpc_address
                                          :native_transport_broadcast_address :broadcast_rpc_address})

(defn ensure-correct-address-field-names
  "DSE 6.0 renamed rpc_address to native_transport_address and
  broadcast_rpc_address to native_transport_broadcast_address.
  We need to make sure we are using the old names if the DSE
  version is < 6.0 or if we are using OSS Cassandra."
  [product datastax-version fields]
  (if (and (= "dse" product) (v/version-is-at-least "6.0" datastax-version))
    fields
    (reduce (fn [new-fields [from-field-name to-field-name]]
              (dissoc (assoc new-fields to-field-name (get new-fields from-field-name))
                      from-field-name))
            fields
            pre-dse-60-field-mappings)))

;; Note, this includes both old and new address field names. We use this
;; to extract the node private properties, regardless of DSE version. When
;; writing these to cassandra-yaml, we use the ensure-correct-address-fields
;; fn above.
(def node-private-props #{:listen_address
                          :broadcast_address
                          :native_transport_address
                          :native_transport_broadcast_address
                          :rpc_address
                          :broadcast_rpc_address
                          :seed_provider
                          :initial_token
                          :auto_bootstrap})

(defn seed-provider-class [product product-version]
  (if (= "cassandra" product)
    "org.apache.cassandra.locator.K8SeedProvider"
    "org.apache.cassandra.locator.SimpleSeedProvider"))

(defmethod enrich-config :cassandra-yaml
  [{:keys [datastax-version product] :or {product "dse"}}
   config-key
   {:keys [cluster-info node-info] :as config-data}]
  (let [default-seed-provider [{:class_name (seed-provider-class product datastax-version)
                                :parameters [{:seeds (:seeds cluster-info)}]}]
        ;; Note, we are using the new address field names since DSE 6.0+
        additional-cassandra-yaml-fields
        (ensure-correct-address-field-names
          product
          datastax-version
          (-> node-info
              (select-keys node-private-props)
              (assoc :cluster_name (:name cluster-info)
                     :seed_provider (get-in config-data
                                            [config-key :seed_provider]
                                            default-seed-provider))))]
    (-> config-data
        ;; make sure no pre-existing values leak into the config output
        (update config-key #(apply dissoc % node-private-props))
        ;; Merge the data into :cassandra-yaml
        (update config-key merge additional-cassandra-yaml-fields))))

(defmethod enrich-config :cassandra-env-sh
  [{:keys [datastax-version product] :or {product "dse"}}
   config-key
   {:keys [jvm-options jvm-server-options] :as config-data}]
  (update config-data config-key merge
          (select-keys
           ;; Since DSE 6.8, jvm.options has been replaced by jvm-server.options
           ;; and version-specific files jvm8-server.options and jvm11-server.options
           ;; Thus, the location of the jmx-port option is now dependent on the
           ;; DSE version.
           (if (v/version-is-at-least "6.8" datastax-version)
             jvm-server-options
             jvm-options)
           [:jmx-port])))

(def workload-keys [:graph-enabled :spark-enabled :solr-enabled])

(defn- get-workload-vars
  [datacenter]
  (data/map-values
    data/as-int
    (select-keys datacenter workload-keys)))

(defn- get-dse-run-as
  "Returns a vector of the [user, group] that cassandra should run as.
  This information comes from the install-options config."
  [config-data]
  (let [{:keys [install-type install-privileges run-dse-as-user run-dse-as-group]}
        (get config-data :install-options {})]
    (if (tarball? install-type)
      (if (= "root" install-privileges)
        [(or run-dse-as-user "cassandra") (or run-dse-as-group "cassandra")]
        ;; Doesn't really make sense for non-root tarballs. We have to use
        ;; the ssh login user/group.
        [nil nil])
      ["cassandra" "cassandra"])))

(defmethod enrich-config :dse-default
  [_ config-key {:keys [datacenter-info] :as config-data}]
  (let [workload-vars (get-workload-vars datacenter-info)
        run-as-vars (zipmap [:cassandra-user :cassandra-group]
                            (get-dse-run-as config-data))]
    (update config-data config-key merge
            workload-vars
            run-as-vars)))

;; this only applies to tarball and is removed when NOT tarball
(defmethod enrich-config :datastax-env-sh
  [_ config-key {:keys [java-setup] :as config-data}]
  (let [install-directory (get-install-directory config-data)
        manage-java (:manage-java java-setup)
        java-vendor (:java-vendor java-setup)]
    (update config-data config-key merge
            {:manage-java manage-java
             :install-directory install-directory
             :java-vendor java-vendor})))

(defmethod enrich-config :cassandra-rackdc-properties
  [_ config-key {:keys [datacenter-info node-info] :as config-data}]
  (update config-data config-key merge
          {:dc   (:name datacenter-info)
           :rack (:rack node-info)}))

(defmulti generate-file-path
  "Generate the absolute file path for the config. Based on instal-type
  (package v tarball) and related settings."
          (fn [_ config-key _] config-key))


(defmethod generate-file-path :default
  [{:keys [definitions]} config-key config-data]
  (let [{:keys [install-type] :or {install-type "package"}}
        (get config-data :install-options)

        install-directory (get-install-directory config-data)

        {:keys [package-path tarball-path]}
        (get definitions config-key)

        empty-path? (or (and (package? install-type)
                             (not (seq package-path)))
                        (and (tarball? install-type)
                             (not (seq tarball-path))))]
    (if empty-path?
      config-data
      (assoc-in config-data [:node-info :file-paths config-key]
                (case install-type
                  "package" package-path
                  "tarball" (str (io/file install-directory "dse" tarball-path)))))))

(def address-yaml-paths {:package-path "/var/lib/datastax-agent/conf/address.yaml"
                         :tarball-path "datastax-agent/conf/address.yaml"})

(defmethod generate-file-path :address-yaml
  [_ config-key config-data]
  (let [{:keys [install-type] :or {install-type "package"}}
        (:install-options config-data)

        install-directory (get-install-directory config-data)]
    (assoc-in config-data [:node-info :file-paths config-key]
              (if (tarball? install-type)
                (str (io/file install-directory (:tarball-path address-yaml-paths)))
                (:package-path address-yaml-paths)))))

(defn- is-directory?
  "Predicate for map-paths that will filter through definition tree paths
  for fields that have {:is_directory true} in their metadata."
  [k v]
  (and (= :is_directory k) (true? v)))

(defn- is-file?
  "Predicate for map-paths that will filter through definition tree paths
  for fields that have {:is_file true} in their metadata."
  [k v]
  (and (= :is_file k) (true? v)))

(defn- is-path?
  "Predicate for map-paths that matches either file or directory fields."
  [k v]
  (or (is-directory? k v) (is-file? k v)))

(defn make-absolute
  "If path is not absolute, prepend it with base-path."
  [base-path path]
  (if (.isAbsolute (io/file path))
    path
    ;; DSE is installed under the "dse" subdirectory under base-path
    (str (io/file base-path path))))

(defn fully-qualify-fn
  "For tarball installations, paths may need to be fully-qualified by
  prepending the install-directory.
  This function takes a map of config data and returns a function that
  fully qualifies paths."
  [config-data]
  (if (tarball-config? config-data)
    (partial make-absolute (get-install-directory config-data))

    ;; don't qualify paths unless it's a tarball install
    identity))

(defn- get-configured-paths*
  "Given a config key, returns a reduce fn that compares a field's
  :default_value from definitions to the actual user config value.
  Adds metadata to the :configured-paths key of the node-info map
  about the paths, including whether it is custom and whether it
  represents a directory."
  [definitions config-key]
  (fn [config-data
       directory-property-path]
    (let [field-metadata (get-in definitions (cons config-key directory-property-path))
          config-key-path (d/property-path->key-path
                           directory-property-path)
          fully-qualify (fully-qualify-fn config-data)

          ;; if the field type is list, convert it to a set
          ;; also ensure the values are fully-qualified
          as-fully-qualified-seq
          (fn [v]
            (if (= "list" (:type field-metadata))
              (map fully-qualify v)
              ;; wrap single value in seq, unless nil
              (if (nil? v) [] (vector (fully-qualify v)))))
          as-fully-qualified-set (comp set as-fully-qualified-seq)
          is-custom? (complement (as-fully-qualified-set (:default_value field-metadata)))
          actual-values (as-fully-qualified-seq (get-in config-data
                                                        (cons config-key config-key-path)))]

      (update-in config-data
                 [:node-info :configured-paths]
                 concat
                 (map
                  (fn [actual-value]
                    (ConfiguredPath. (get-in definitions [config-key :display-name])
                                     config-key-path
                                     actual-value
                                     (is-custom? actual-value)
                                     (boolean (:is_directory field-metadata))))
                  actual-values)))))

(defn fully-qualify-paths
  "Ensures that tarball paths are fully qualified."
  [{:keys [definitions]} config-key config-data]
  (if (tarball-config? config-data)
    (let [fully-qualify (fully-qualify-fn config-data)

          definitions-for-config
          (select-keys (get definitions config-key) [:properties])

          ;; Get the property paths for config properties that represent
          ;; files and directories.
          path-matchers
          (map (comp #(d/preference-path-matcher % definitions-for-config)
                     butlast)
               (data/map-paths is-path? definitions-for-config))]
      ;; As long as the path matches one of the path-matchers predicates,
      ;; it is a value that needs to be fully-qualified.
      (if-let [is-path-value? (and (seq path-matchers) (apply some-fn path-matchers))]
        ;; Reduce over all paths to leaf nodes in the config.
        ;; The preference path is tested against the path matchers to see if the leaf
        ;; value needs to be transformed into a fully-qualified path.
        ;;
        ;; However, if there are no path-matchers (because there are no path-like values
        ;; in this particular config), nothing needs to be done.
        (reduce
         (fn [config-data preference-path]
           (if (is-path-value? preference-path)
             (update-in config-data
                        (cons config-key preference-path)
                        fully-qualify)
             config-data))
         config-data
         (data/all-paths (get config-data config-key)))
        ;; No path-matchers means this config has no directory or file properties
        config-data))

    ;; Change nothing if it's a package install
    config-data))

(defn get-configured-paths
  "For the given config-key, finds all configured filesystem path-like values
  and adds certain metadata for them to the node-info."
  [{:keys [definitions]} config-key config-data]
  (if-let [definitions-for-config
           (select-keys (get definitions config-key) [:properties])]
    (let [;; Get path to a definition properties that represent directories and files
          ;; Note that the call to map paths will return a path with :is_directory (or :is_file)
          ;; at the end, so mapping butlast over the seq will trim that.
          ;; Example entry before trimming:
          ;; [:properties :data_file_directories :is_directory]
          directory-file-property-paths
          (map butlast (data/map-paths is-path? definitions-for-config))]
      ;; For each directory/file property-path, get the :default_value and the actual
      ;; value in config. Include metadata in node-info indicating the configured path,
      ;; whether or not it is custom, and whether or not it is a directory or file.
      (reduce
        (get-configured-paths* definitions config-key)
        config-data
        directory-file-property-paths))
    config-data))

(defn build-configs*
  "Perform data enrichment for each config key in the config-data."
  [definitions-data config-data]
  (reduce (fn [config-data config-key]
            (->> config-data
                 (enrich-config definitions-data config-key)
                 (generate-file-path definitions-data config-key)
                 (get-configured-paths definitions-data config-key)
                 (fully-qualify-paths definitions-data config-key)))
          config-data (keys config-data)))

(defn prune-config-keys
  "Remove config keys that are not applicable to the target installation
  method (package v tarball)."
  [config-data]
  (if (tarball-config? config-data)
    (dissoc config-data :dse-default)
    (dissoc config-data :datastax-env-sh)))

(defn build-configs
  "Enriches the config-data by merging in defaults (where there are no
   user-configured values, sometimes referred to as 'overrides'),
   model-info (things that aren't defined in definition files, like
   listen_address), and other special snowflakes like :address-yaml."
  [definitions-data config-data]
  (let [definitions-data (maybe-use-tarball-defaults definitions-data config-data)]
    (->> config-data
         (valid-config-keys? definitions-data)
         (with-defaults definitions-data)
         (remove-unmanaged-config-files!)
         (prune-config-keys)
         (build-configs* definitions-data))))
