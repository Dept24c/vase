(ns vase
  (:require [clojure.spec :as spec]
            [vase.routes :as routes]
            [vase.datomic :as datomic]
            [vase.util :as util]
            [vase.literals]
            [vase.spec]))

(defn load-edn-resource
  "Given a resource name, loads a descriptor or app-spec,
  using the proper readers to get support for Vase literals."
  [res]
  (util/edn-resource res))

(defn ensure-schema
  "Given an api-spec or a collection of app-specs,
  extract the schema norms, ensure they conform, and idempotently
  transact them into the Datomic DB.
  Returns a map of {'db-uri' {:connection datomic-conn, :norms {... all merged norms ..}}}."
  [spec-or-specs]
  (let [edn-specs (if (sequential? spec-or-specs) spec-or-specs [spec-or-specs])
        uri-norms (reduce (fn [acc spec]
                            (let [uri (:datomic-uri spec)
                                  norms (get-in spec [:descriptor :vase/norms])]
                              (if (contains? acc uri)
                                ;; It is expected that norm chunks are complete.
                                ;;   A single chunk cannot be spread across files,
                                ;;   which is why we're using `merge` and not `merge-with concat`
                                (update-in acc [uri] #(merge % norms))
                                (assoc acc uri norms))))
                          {}
                          edn-specs)]
    (reduce (fn [acc [uri norms]]
              (let [conn (datomic/connect uri)]
                (datomic/ensure-schema conn norms)
                (assoc acc uri {:connection conn
                                :norms norms})))
            {}
            uri-norms)))

(defn specs
  "Given a app-spec or collection of app-specs,
  extract all defined clojure.specs and evaluate them,
  placing them in clojure.spec's registry."
  [spec-or-specs]
  (let [edn-specs (if (sequential? spec-or-specs) spec-or-specs [spec-or-specs])
        descriptors (map :descriptor edn-specs)]
    (doseq [descriptor descriptors]
      (when-let [specs (:vase/specs descriptor)]
        (doseq [[k specv] specs]
          (let [sv (cond
                     (spec/spec? specv) specv
                     (list? specv) (eval specv)
                     (symbol? specv) (resolve specv)
                     :else specv)]
            (eval `(spec/def ~k ~sv))))))))

(defn routes
  "Return a seq of route vectors for Pedestal's table routing syntax. Routes
  will all begin with `api-root/:api-namespace/api-name-tag`.

  `spec-or-specs` is either a single app-spec (as a map) or a collection of app-specs.

  The routes will support all the operations defined in the
  spec. Callers should treat the format of these routes as
  opaque. They may change in number, quantity, or layout."
  ([api-root spec-or-specs]
   (routes api-root spec-or-specs {}))
  ([api-root spec-or-specs opts]
   (let [{:keys [make-interceptors-fn]
          :or {make-interceptors-fn identity}} opts
         specs (if (sequential? spec-or-specs) spec-or-specs [spec-or-specs])
         ;; We need to "unpack" all the :activated-apis
         ;;  From this part onward, :activated-apis is a single, scalar; a keyword
         expanded-specs (mapcat (fn [spec]
                                  (if (sequential? (:activated-apis spec))
                                    (mapv #(assoc spec :activated-apis %) (:activated-apis spec))
                                    [spec]))
                                specs)
         routes    (mapcat (partial routes/spec-routes api-root make-interceptors-fn) expanded-specs)
         api-route (routes/api-description-route
                     api-root
                     make-interceptors-fn
                     routes
                     :describe-apis)]
     (cons api-route routes))))

(spec/fdef routes
           :args (spec/or :no-options (spec/cat :api-route vase.spec/valid-uri?
                                                :spec-or-specs (spec/or :single-spec ::vase.spec/spec
                                                                        :multiple-specs (spec/* ::vase.spec/spec)))
                          :with-options (spec/cat :api-route vase.spec/valid-uri?
                                                  :spec-or-specs (spec/or :single-spec ::vase.spec/spec
                                                                          :multiple-specs (spec/* ::vase.spec/spec))
                                                  :opts map?))
           :ret  ::vase.spec/route-table)
