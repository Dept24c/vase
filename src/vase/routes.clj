(ns vase.routes
  (:require [io.pedestal.http :as http]
            [io.pedestal.interceptor :as i]
            [io.pedestal.http.body-params :as body-params]
            [vase.datomic :as datomic]
            [vase.interceptor :as interceptor]
            [clojure.string :as string]))

(defn- describe-api
  "Return a list of all active routes.
  Optionally filter the list with the query param, `f`, which is a fuzzy match
  string value"
  [routes]
  (i/interceptor
   {:enter (fn [context]
             (let [{:keys [f sep edn]
                    :or {f "" sep "<br/>" edn false}} (get-in context [:request :query-params])
                   results (mapv #(take 2 %) routes)]
               (assoc context :response
                      (if edn
                        (http/edn-response results)
                        {:status 200
                         :body   (string/join sep (map #(string/join " " %) results))}))))}))

(def ^:private common-api-interceptors
  [interceptor/attach-received-time
   interceptor/attach-request-id
   http/json-body])

(defn- app-interceptors
  [spec]
  (let [{:keys [descriptor activated-apis datomic-uri]} spec
        datomic-conn       (datomic/connect datomic-uri)
        headers-to-forward (get-in descriptor [:vase/apis activated-apis :vase.api/forward-headers] [])
        headers-to-forward (conj headers-to-forward interceptor/request-id-header)
        version-interceptors (mapv i/interceptor (get-in descriptor [:vase/apis activated-apis :vase.api/interceptors] []))
        base-interceptors (conj common-api-interceptors
                                (datomic/insert-datomic datomic-conn)
                                (body-params/body-params (body-params/default-parser-map :edn-options {:readers *data-readers*}))
                                (interceptor/forward-headers headers-to-forward))]
    (into base-interceptors
          version-interceptors)))

(defn- specified-routes
  [spec]
  (let [{:keys [activated-apis descriptor]} spec]
    (get-in descriptor [:vase/apis activated-apis :vase.api/routes])))

(defn- api-routes
  "Given a descriptor map, an app-name keyword, and a version keyword,
  return route vectors in Pedestal's tabular format. Routes will all be
  subordinated under `base`"
  [base spec make-interceptors-fn]
  (let [common (app-interceptors spec)]
    (for [[path verb-map] (specified-routes spec)
          [verb action]   verb-map
          :let [interceptors (if (vector? action)
                               (into common (map i/interceptor action))
                               (conj common (i/interceptor action)))]]
      [(str base path) verb (make-interceptors-fn interceptors)])))

(defn- api-base
  [api-root spec]
  (let [{:keys [activated-apis]} spec]
   (str api-root "/" (namespace activated-apis) "/" (name activated-apis))))

(defn- api-description-route-name
  [spec]
  (let [{:keys [activated-apis]} spec]
    (keyword (str (namespace activated-apis)
                  "." (name activated-apis))
             "describe")))

(defn api-description-route
  [api-root make-interceptors-fn routes route-name]
  [api-root
   :get
   (make-interceptors-fn
     (conj common-api-interceptors (describe-api routes)))
   :route-name
   route-name])

(defn spec-routes
  "Return a seq of route vectors from a single specification"
  [api-root make-interceptors-fn spec]
  (if (nil? (:activated-apis spec))
    []
    (let [app-version-root   (api-base api-root spec)
          app-version-routes (api-routes app-version-root spec make-interceptors-fn)
          app-api-route      (api-description-route app-version-root
                                                    make-interceptors-fn
                                                    app-version-routes
                                                    (api-description-route-name spec))]
      app-version-routes)))
