(ns lpd.protocol
  (:require [clojure.string :as s])
  (:import java.io.ByteArrayOutputStream))

(defrecord PrintJob [data])

(defprotocol IResumeQueueHandler
  (resume-queue [_ queue]))

(defprotocol IPrintJobHandler
  (accept-job [_ queue job]
    "Called after a complete job was received. This is the main entry
    point for "))

(defprotocol IListPrintQueueHandler
  (list-queue [_ queue]
    "Called for 0x03 or 0x04 (\"Send queue state\"). Should return
    a (possibly empty) list of queued jobs."))

(defn handler?
  "Checks if H is a valid handler (e.g. at minimum implementes
  IPrintJobHandler)"
  [h]
  (satisfies? IPrintJobHandler h))

;;; Internal API

(defn ^:private parse-string [bs]
  (String. bs "UTF-8"))

(defn ^:private parse-size [bs]
  (-> bs
      (parse-string)
      (Integer/parseInt)))

(def queue-terminator? #{9 11 12 32})
(def linefeed? #{10})

(defn ^:private split-args [args]
  (if (= [10] args)
    []
    (loop [acc [], args args]
      (let [[arg r] (split-with (complement #(or (queue-terminator? %)
                                                 (linefeed? %)))
                                args)]
        (cond
          (= [10] r)
          (conj acc arg)
          
          (seq r)
          (recur (conj acc arg) (rest r))

          true
          acc)))))

(defn ^:private parse-command [bs]
  (when-not (linefeed? (last bs))
    (throw (ex-info "Command not terminated by LF."
                    {:bytes bs})))
  (->> (rest bs)
       (split-args)
       (map byte-array)
       (concat [(aget bs 0)])
       (vec)))

(defmulti ^:private parse-control-line (fn [line] (first line)))

(defn ^:private parse-control-file [s]
  (reduce (fn [m line]
            (merge m (parse-control-line line)))
          {}
          (s/split-lines s)))

(defmethod parse-control-line :default
  [line]
  (println "Unimplemented control line" (pr-str line))
  {})

(defmethod parse-control-line \N [line]
  {:source-filename (subs line 1)})

(defmethod parse-control-line \J [line]
  {:banner-name (subs line 1)})

(defmethod parse-control-line \P [line]
  {:user (subs line 1)})

(defmethod parse-control-line \H [line]
  {:host (subs line 1)})

(defmethod parse-control-line \U [line]
  {:unlink? true})

(defmethod parse-control-line \d [line]
  {:type :dvi})

(defmethod parse-control-line \f [line]
  {:type :text})

(defmethod parse-control-line \l [line]
  {:type :raw})

(defmethod parse-control-line \o [line]
  {:type :postscript})

(defn ^:private read-data-file [in size]
  (let [bs (byte-array size)]
    (loop [n (.read in bs 0 size)]
      (when (< n size)
        (recur (+ n (.read in bs n (- size n))))))
    (.read in)
    bs))

(defn ^:private read-control-file [in size]
  (-> (read-data-file in size)
      (parse-string)
      (parse-control-file)))

(defn ^:private handle-subcommand [command job in out]
  (let [op (first command)]
    (case op
      0x01
      (assoc job :canceled? true)
      
      (0x02 0x03)
      (let [[_ size name] command
            size (parse-size size)
            name (parse-string name)]
        (when-not (pos? size)
          (.write out 0x01)
          (throw (ex-info "No size sent by client."
                          {:command command, :job job})))
        (.write out 0x00)
        (let [job (assoc job :data-file name)
              job (case op
                    0x02 (merge job
                                (read-control-file in size))
                    0x03 (assoc job
                                :data (read-data-file in size)))]
          (.write out 0x00)
          job))
      (do
        (println "Unknown subcommand:" (pr-str command))
        (.write out 0x01)
        job))))

(declare read-command)

(defn ^:private handle-add-job [handler queue in out]
  (.write out 0x00)
  (let [job (loop [job (map->PrintJob {})]
              (if-let [job (some-> (read-command in)
                                   (handle-subcommand job in out))]
                (recur job)
                job))]
    (when (satisfies? IPrintJobHandler handler)
      (accept-job handler queue job))))

(defn ^:private handle-resume-queue [handler queue in out]
  (when (satisfies? IResumeQueueHandler handler)
    (resume-queue queue handler)))

(defn ^:private handle-list-queue [handler queue in out]
  (doto out
    (.write (if (satisfies? IListPrintQueueHandler handler)
              (s/join \newline (map pr-str (list-queue handler queue)))
              "Not implemented"))
    (.flush)
    (.close)))

;;; "Public" API

(defn read-command [in]
  (let [os (ByteArrayOutputStream.)]
    (loop [b (.read in)]
      (when-not (= -1 b)
        (.write os b))
      (when-not (contains? #{-1 10} b)
        (recur (.read in))))
    (let [bs (.toByteArray os)]
      (when (seq bs)
        (parse-command bs)))))

(defn handle-command [command handler in out]
  {:pre [(handler? handler)]}
  (let [[op queue & _] command
        queue (parse-string queue)]
    (case op
      0x01        (handle-resume-queue handler queue in out)
      0x02        (handle-add-job handler queue in out)
      (0x03 0x04) (handle-list-queue handler queue handler in out)
      (do
        (println "got unimplemented command:" (pr-str command))
        (.write out 0x01)
        (.close out)))))

