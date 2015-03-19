(ns lpd.protocol-test
  (:require [clojure.test :refer :all]
            [lpd.protocol :refer :all]
            [clojure.string :as s])
  (:import java.io.ByteArrayInputStream))

(defn ^:private b->is [bs]
  (ByteArrayInputStream. (byte-array (map byte bs))))

(defn ^:private parse-string [bs]
  (String. bs "UTF-8"))

(def ^:private lf 10)

(defn ^:private test-read [bs]
  (-> bs b->is read-command))

(def +whitespaces+ [9 11 12 32])

(deftest test-read-command
  (testing "empty command"
    (is (nil? (test-read []))))
  (testing "no linefeed"
    (is (thrown? Exception (-> [42 102 111 111] (b->is) (read-command)))))
  (testing "single byte command"
    (doseq [b [0x01 0x02 0x03 0x04 0x05]]
      (is (= [b] (test-read [b lf])))))
  (testing "queue names"
    (let [[op queue] (-> [42 102 111 111 lf] (b->is) (read-command))]
      (is (= 42 op))
      (is (= "foo" (parse-string queue)))))
  (testing "command with arg"
    (doseq [w +whitespaces+]
      (let [command (test-read [42 w 102 111 111 lf])
            [op queue & args] command]
        (is (= 42 op))
        (is (= "" (parse-string queue)))
        (is (= ["foo"] (mapv parse-string args))))))
  (testing "command with multiple args"
    (doseq [w +whitespaces+]
      (let [command (test-read [42 w 102 111 111 w 102 111 111 lf])
            [cmd queue & args] command]
        (is (= 42 cmd))
        (is (= "" (parse-string queue)))
        (is (= ["foo" "foo"] (mapv parse-string args))))))
  (testing "command with empty args"
    (doseq [w +whitespaces+]
      (let [command (test-read [42 w 102 111 111 w w w 102 111 111 lf
                                ])
            [cmd queue & args] command]
        (is (= 42 cmd))
        (is (= "" (parse-string queue)))
        (is (= ["foo" "" "" "foo"] (mapv parse-string args))))))
  (testing "command args with queue")
  (doseq [w +whitespaces+]
    (let [command (test-read [42 102 102 102 w 102 111 111 w 102 111 111 lf])
          [cmd queue & args] command]
      (is (= "fff" (parse-string queue)))
      (is (= 42 cmd))
      (is (= ["foo" "foo"] (mapv parse-string args))))))
