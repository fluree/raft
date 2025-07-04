(require '[taoensso.nippy :as nippy]
         '[clj-http.client :as http])

(let [command {:f :read :value nil :key :x}
      payload (nippy/freeze command)
      url "http://localhost:7001/command"]
  (println "Sending command:" command)
  (try
    (let [response (http/post url
                             {:body payload
                              :headers {"Content-Type" "application/octet-stream"}
                              :as :byte-array
                              :socket-timeout 10000
                              :connection-timeout 1000})]
      (println "Response status:" (:status response))
      (println "Response body:" (nippy/thaw (:body response))))
    (catch Exception e
      (println "Error:" (.getMessage e)))))