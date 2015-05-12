(ns lpd.server
  (:require [lpd.protocol :as protocol])
  (:import java.lang.Thread
           java.io.IOException
           java.util.concurrent.Executors
           java.util.concurrent.TimeUnit))

(defn make-server [{:keys [host port handler pool-size]}]
  {:pre [(string? host) (< 0 port 65535)]}
  ;; Check `handler' is a valid `IPrintJobHandler'
  (when-not (protocol/handler? (if (var? handler)
                                 (var-get handler)
                                 handler))
    (throw (ex-info "Given handler doesn't implement IPrintJobHandler."
                    {:handler handler})))
  {:host host
   :port port
   :pool-size pool-size
   :handler handler})

(defn ^:private shutdown-pool [server]
  (when-let [pool (:pool server)]
    (.shutdown pool)
    (.awaitTermination pool 10 TimeUnit/SECONDS)))

(defn ^:private command-handler [client server]
  (try
    (let [in (.getInputStream client)
          out (.getOutputStream client)
          command (-> in protocol/read-command)
          handler (let [h (:handler server)]
                    (if (var? h)
                      (var-get h)
                      h))]
      (protocol/handle-command command handler in out))
    (finally
      (println "Closing connection to" client)
      (.close client))))

(defn start-server [server]
  (let [socket (java.net.ServerSocket.
                (:port server) 0
                (java.net.InetAddress/getByName
                 (:host server)))
        pool (when-let [n (:pool-size server)]
               (Executors/newFixedThreadPool n))
        server (assoc server
                      :socket socket
                      :pool pool)]
    (-> (fn []
          (try
            (while true
              (let [client (.accept socket)
                    f #(command-handler client server)]
                (println "Got new client:" client)
                (if-let [pool (:pool server)]
                  (.execute pool f)
                  (f))))
            (catch IOException e
              (println "Caught exception:" e)
              (shutdown-pool server))))
        (Thread.)
        (.start))
    server))

(defn stop-server [server]
  (.close (:socket server))
  (shutdown-pool server)
  (dissoc server :socket :pool))

(comment
  (def my-handler (reify
                    protocol/IPrintJobHandler
                    (accept-job [_ queue job]
                      (println "got job on queue" queue)
                      (prn job))))
  (def my-server (make-server {:host "localhost"
                               :port 6332
                               :handler #'my-handler}))
  
  (alter-var-root #'my-server start-server)
  (alter-var-root #'my-server stop-server))
