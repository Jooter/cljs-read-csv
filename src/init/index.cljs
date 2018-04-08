(ns init.index
  (:refer-clojure :exclude [atom])
  (:require [freactive.core :refer [atom cursor]]
            [freactive.dom :as dom]
            [clojure.string :as s]
            [cljs.core.async :refer [put! chan <! >!]])
  (:import [goog.labs.format csv])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [freactive.macros :refer [rx]]))

(defn log1 [& args] (js/console.log (pr-str args)))

;; from https://gist.github.com/paultopia/6fc396884c223b619f2e2ef199866fdd
;; derived from https://mrmcc3.github.io/post/csv-with-clojurescript/
;; and based on reagent-frontend template. 

;; dependencies from project.clj in addition to clojure, clojurescript, and reagent:
;; [org.clojure/core.async "0.2.395"]

;; atom to store file contents

(def file-data (atom nil))

;; transducer to stick on a core-async channel to manipulate all the weird javascript
;; event objects --- basically just takes the array of file objects or something
;; that the incomprehensible browser API creates and grabs the first one, then resets things.
(def first-file
  (map (fn [e]
         (let [target (.-currentTarget e)
               file (-> target .-files (aget 0))]
           (set! (.-value target) "")
           (prn
            (aget file "name")
            (aget file "size"))
           file))))

;; transducer to get text out of file object.
(def extract-result
  #_(map #(-> % .-target .-result (s/split #"\n") count))
  #_(map #(-> % .-target .-result))
  (map #(-> % .-target .-result
            js/Int8Array.
            ((fn [a] (js/String.fromCharCode.apply nil a)))
            (csv/parse nil ",") 
            js->clj
            ))
  #_(map #(-> % .-target .-result (csv/parse nil ",") js->clj))
  )

;; two core.async channels to take file array and then file and apply above transducers to them.
(def upload-reqs (chan 1 first-file))
(def file-reads (chan 1 extract-result))

;; function to call when a file event appears: stick it on the upload-reqs channel (which will use the transducer to grab the first file)
(defn put-upload [e]
  (prn :upload (-> e .-target .-value))
  (put! upload-reqs e))

;; sit around in a loop waiting for a file to appear in the upload-reqs channel, read any such file, and when the read is successful, stick the file on the file-reads channel.
(go-loop []
  (let [reader (js/FileReader.)
        file (<! upload-reqs)]
    #_(set! (.-onload reader) #(put! file-reads %))
    (set! (.-onloadend reader) #(put! file-reads %))

    #_(.readAsText reader file)
    #_(.readAsBinaryString reader file)
    #_(.readAsDataURL reader file)
    (.readAsArrayBuffer reader file)
    (recur)))

;; sit around in a loop waiting for a string to appear in the file-reads channel and put it in the state atom to be read by reagent and rendered on the page.
(go-loop []
  (reset! file-data (<! file-reads))
  #_(swap! file-data concat (<! file-reads))
  (recur))

;; input component to allow users to upload file.
(defn input-component []
  [:input {:type "file" ;; :id "file" :accept ".csv" :name "file"
           :on-change put-upload}])

;; ------------------------- 
;; Views

(defn view []
  [:div
   [:p "Read csv file"]
   (input-component)
   (rx
    (do
      ; (prn :f  @file-data)
      (condp #(%1 %2) @file-data
        nil? [:p "nil"]
        number? [:p "number:" (str @file-data)]
        string? [:p "string:" @file-data]

        sequential?
        [:div
         [:p "count:" (-> @file-data count str)]
         [:table {:border 1}
          (for [l (take 5 @file-data)]
            [:tr
             (for [c l] [:td c])
             ])]]

        (str "else:" @file-data)
        )))
   ])

;; -------------------------
;; Initialize app

(set!
 (.-onload js/window)
 (fn []
   (let [root
         (dom/append-child! (.-body js/document) [:div#root])]

     (aset js/document "title" "Read csv by cljs")

     (dom/mount! root (view))

     (log1 "page has been mounted")
     )))
