(ns eden.mcp.auth
  "Authentication middleware for MCP server"
  (:require [ring.util.response :as response]
            [clojure.string :as str]))

(defn extract-secret-from-uri
  "Extract secret from URI path like /mcp/{secret}/rpc"
  [uri]
  (when-let [matches (re-matches #"/mcp/([^/]+)/.*" uri)]
    (second matches)))

(defn valid-secret?
  "Validate the provided secret against expected"
  [provided expected]
  (and (not (str/blank? provided))
       (not (str/blank? expected))
       (= provided expected)))

(defn wrap-auth
  "Authentication wrapper for handlers"
  [handler config request]
  (let [uri (:uri request)
        provided-secret (extract-secret-from-uri uri)
        expected-secret (:secret config)]
    (if (valid-secret? provided-secret expected-secret)
      (handler config request)
      (-> (response/response {:error "Invalid authentication"})
          (response/status 403)
          (response/content-type "application/json")))))