(ns sitefox.web
  "Functions to start the webserver and create routes."
  (:require
    [sitefox.util :refer [env]]
    [sitefox.db :as db]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["path" :as path]
    ["process" :as process]
    ["express" :as express]
    ["cookie-parser" :as cookies]
    ["body-parser" :as body-parser]
    ["serve-static" :as serve-static]
    ["express-session" :as session]
    ["morgan" :as morgan]
    ["rotating-file-stream" :as rfs]))

(def ^:no-doc server-dir (or js/__dirname "./"))

(defn ^:no-doc create-store [kv]
  (let [e (session/Store.)]
    (aset e "destroy" (fn [sid callback]
                        (p/let [result (j/call kv :delete sid)]
                          (when callback (callback))
                          result)))
    (aset e "get" (fn [sid callback]
                    (p/let [result (j/call kv :get sid)]
                      (callback nil result)
                      result)))
    (aset e "set" (fn [sid session callback]
                    (p/let [result (j/call kv :set sid session)]
                      (when callback (callback))
                      result)))
    (aset e "touch" (fn [sid session callback]
                      (p/let [result (j/call kv :set sid session)]
                        (when callback (callback))
                        result)))
    (aset e "clear" (fn [callback]
                      (p/let [result (js/call kv :clear)]
                        (when callback (callback))
                        result)))
    e))

(defn add-default-middleware
  "Set up default express middleware for:

  * Writing rotating logs to `logs/access.log`.
  * Setting up sessions in the configured database.
  * Parse cookies and body."
  [app]
  (let [logs (path/join server-dir "/logs")
        access-log (.createStream rfs "access.log" #js {:interval "7d" :path logs})
        kv-session (db/kv "session")
        store (create-store kv-session)]
    ; set up sessions table
    (.use app (session #js {:secret (env "SECRET" "DEVMODE")
                            :saveUninitialized false
                            :resave true
                            :cookie #js {:secure "auto"
                                         :httpOnly true
                                         ; 10 years
                                         :maxAge (* 10 365 24 60 60 1000)}
                            :store store}))
    ; set up logging
    (.use app (morgan "combined" #js {:stream access-log})))
  ; configure sane server defaults
  (.set app "trust proxy" "loopback")
  (.use app (cookies (env "SECRET" "DEVMODE")))
  ; json body parser
  (.use app (.json body-parser #js {:limit "10mb" :extended true :parameterLimit 1000}))
  app)

(defn static-folder
  "Express middleware to statically serve a `dir` on a `route` relative to working dir."
  [app route dir]
  (.use app route (serve-static (path/join server-dir dir)))
  app)

(defn reset-routes
  "Remove all routes in the current app and re-add the default middleware.
  Useful for hot-reloading code."
  [app]
  (let [router (aget app "_router")]
    (when router
      (js/console.error (str "Deleting " (aget router "stack" "length") " routes"))
      (aset app "_router" nil))
    (add-default-middleware app)))

(defn create
  "Create a new express app and add the default middleware."
  []
  (-> (express)
      (add-default-middleware)))

(defn serve
  "Start serving an express app.

  Configure `BIND_ADDRESS` and `PORT` with environment variables.
  They default to `127.0.0.1:8000`."
  [app]
  (let [host (env "BIND_ADDRESS" "127.0.0.1")
        port (env "PORT" "8000")
        srv (.bind (aget app "listen") app port host)]
    (js/Promise.
      (fn [res err]
        (srv (fn []
               (js/console.log "Web server started: " (str "http://" host ":" port))
               (res #js [host port])))))))

(defn start
  "Create a new express app and start serving it.
  Runs (create) and then (serve) on the result.
  Returns a promise which resolves with [app host port] once the server is running."
  []
  (let [app (create)]
    (->
      (serve app)
      (.then (fn [host port] [app host port])))))

(defn build-absolute-uri
  "Creates an absolute URL including host and port.
  Use inside a route: `(build-absolute-uri req \"/somewhere\")`"
  [req path]
  (let [hostname (aget req "hostname")
        host (aget req "headers" "host")]
    (str (aget req "protocol") "://"
         (if (not= hostname "localhost") hostname host)
         (if (not= (aget path 0) "/") "/")
         path)))

(defn strip-slash-redirect
  "Express middleware to strip slashes from the end of any URL by redirecting to the non-slash version.
  Use: `(.use app strip-slash-redirect)"
  [req res n]
  (let [path (aget req "path")
        url (aget req "url")]
    (if (and
          (= (last path) "/")
          (> (aget path "length") 1))
      (.redirect res 301 (str (.slice path 0 -1) (.slice url (aget path "length"))))
      (n))))
