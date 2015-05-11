# clojure-lpd

A pure Clojure implementation of the server-side of the Line Printer
Daemon protocol.

LPD is a simple network printing protocol described in
[RFC 1179](https://www.ietf.org/rfc/rfc1179.txt). 

The main purpose of this library is the creation of virtual network
printers with a Clojure backend. This is useful to allow clients to
print to a Clojure application where the data (usually Postscript or
PDF) is handled further.

## Usage

The basic protocol is implemented in the `lpd.protocol` namespace. For
most use cases, `lpd.server` implements a basic server.

The following example starts a server on port 6331 which will accept
all incoming print jobs and log them to standard out:

```clojure
(require '[lpd.server :refer [make-server start-server]]
         '[lpd.protocol :as protocol])

;;; Create a 'handler' object. `accept-job' will be called for every
;;; received print job.
(def my-handler
  (reify
    protocol/IPrintJobHandler
    (accept-job [_ queue job]
      ;; `queue' is the name of the printer queue a client prints on
      (println "Got a job on" queue)
      ;; `job' is a PrintJob containing the job-data (a byte-array)
      ;; under the key :data and other information about the job
      (prn job))))

(def server (make-server {:host "localhost"
                          :port 6331
                          :handler my-handler}))

;;; Starts the server on a new thread. To stop the server, replace
;;; `start-server' with `stop-server'
(alter-var-root #'server start-server)
```

## License

Copyright © 2015 bevuta IT GmbH

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
