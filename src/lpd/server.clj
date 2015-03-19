(ns lpd.server
  (:require [lpd.protocol :as protocol])
  (:import java.lang.Thread
           java.io.IOException
           java.util.concurrent.Executors
           java.util.concurrent.TimeUnit))

(defn make-server [{:keys [host port handler pool-size]}]
  {:pre [(protocol/handler? handler)]}
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
          command (-> in protocol/read-command)]
      (protocol/handle-command command (:handler server) in out))
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