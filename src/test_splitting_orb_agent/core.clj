(ns test-splitting-orb-agent.core
  (:require [clj-http.client :as client]
            [clojure.pprint :as p]
            [clojure.string :as string]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.zip :as zip]
            [clojure.edn :as edn]
            [clojure.tools.cli :refer [parse-opts]])
  (:use ring.middleware.params
        ring.util.response
        ring.adapter.jetty)
  (:gen-class))

(defn page [params]
  (let [{circle-token "circle-token" organization "organization" project "project" vcs "vcs" test-files "test-files" node-index "node-index" node-total "node-total"} params]

    (defn pipelines-url
      [vcs organization project]
      (str
        "https://circleci.com/api/v2/project/"
        vcs "/"
        organization "/"
        project
        "/pipeline"))

    (defn get-pipelines
      [req-opts vcs organization project]
      (client/get (pipelines-url vcs organization project) req-opts))

    (defn pipeline-workflows-url 
      [pipeline-number]
      (str
        "https://circleci.com/api/v2/pipeline/"
        pipeline-number
        "/workflow"))

    (defn get-workflows
      ([req-opts pipeline-id] (get-workflows pipeline-id nil))
      ([req-opts pipeline-id next-page-token] (get-workflows pipeline-id next-page-token []))
      ([req-opts pipeline-id next-page-token workflows]

        (def resp 
          (client/get
            (pipeline-workflows-url pipeline-id) 
            (if (some? next-page-token)
              (merge req-opts {:query-params {"page-token" next-page-token}})
              req-opts)))

        (let [{{items :items next-page-token :next-page-token} :body} resp]
          (if (some? next-page-token)
            (recur get-workflows pipeline-id next-page-token (into [] (concat workflows items)))
            (into [] (concat workflows items))))))


    (defn workflow-jobs-url
      [workflow-id]
      (str
        "https://circleci.com/api/v2/workflow/"
        workflow-id
        "/job"))

    (defn get-workflow-jobs
      ([req-opts workflow-id] (get-workflow-jobs workflow-id nil))
      ([req-opts workflow-id next-page-token] (get-workflow-jobs workflow-id next-page-token []))
      ([req-opts workflow-id next-page-token jobs]
        (def resp
          (client/get
            (workflow-jobs-url workflow-id)
            (if (some? next-page-token)
              (merge req-opts {:query-params {"page-token" next-page-token}})
              req-opts)))
          
        (let [{{items :items next-page-token :next-page-token} :body} resp]
          (if (some? next-page-token)
            (recur get-workflow-jobs workflow-id next-page-token (into [] (concat jobs items)))
            (into [] (concat jobs items))))))

    (defn job-artifacts-url
      [vcs organization project job-number]
      (str
        "https://circleci.com/api/v2/project/"
        vcs "/"
        organization "/"
        project "/"
        job-number
        "/artifacts"))

    (defn get-job-artifacts
      ([req-opts vcs organization project job-number] (get-job-artifacts job-number nil))
      ([req-opts vcs organization project job-number next-page-token] (get-job-artifacts job-number next-page-token []))
      ([req-opts vcs organization project job-number next-page-token metadatas]
        (def resp
          (client/get
            (job-artifacts-url vcs organization project job-number)
            (if (some? next-page-token)
              (merge req-opts {:query-params {"page-token" next-page-token}})
              req-opts)))
        (let [{{items :items next-page-token :next-page-token} :body} resp]
          (if (some? next-page-token)
            (recur get-job-artifacts vcs organization project job-number next-page-token (into [] (concat metadatas items)))
            (into [] (concat metadatas items))))))

    (defn get-test-data
      [req-opts url]
      (get (client/get url req-opts) :body))

    (defn parse-test-xml-data
      [xml-data]
      (def testsuites (xml/parse-str (clojure.string/trim xml-data)))
      (def testsuites-zip (zip/xml-zip testsuites))

      (map zip/node
        (zx/xml-> testsuites-zip :testcase)))

    (defn add-to-filename-map
      [filename-map testcase]

      (def filename-hash
        (get-in testcase [:attrs :file]))

      (def names
        (assoc
          (get-in filename-map [filename-hash :names] {})
          (get-in testcase [:attrs :name])
          {:time (get-in testcase [:attrs :time])}))

      (def new-filename-map
        (assoc
          filename-map
          (get-in testcase [:attrs :file])
          {:names names}))

      new-filename-map)

    (defn get-time-totals
      [test-files filename-map]
      (sort-by
        second >
        (map (fn [test-file]
               (if (contains? filename-map test-file)
                 (reduce
                   (fn [acc [_ namee]]
                     [test-file (+ (second acc) (edn/read-string (get namee :time 0)))])
                   [test-file 0]
                   (get-in filename-map [test-file :names]))
                 [test-file 0])) test-files)))

    (defn fill-buckets
      [time-totals buckets node-index node-total]
      (let [[time-total & re-time-totals] time-totals]
        (def new-buckets (update buckets node-index conj (first time-total)))
        (def index (mod (+ node-index 1) node-total))
        (if (nil? re-time-totals) new-buckets
          (fill-buckets re-time-totals new-buckets index node-total))))

    (def req-opts {:basic-auth [circle-token ""]
                   :headers {"Circle-Token" circle-token}
                   :as :json
                   :async? false
                   :accept :json})
    (def artifacts-req-opts {:headers {"Circle-Token" circle-token}})

    (def get-pipelines (partial get-pipelines req-opts))
    (def get-workflows (partial get-workflows req-opts))
    (def get-workflow-jobs (partial get-workflow-jobs req-opts))
    (def get-job-artifacts (partial get-job-artifacts req-opts vcs organization project))
    (def get-test-data (partial get-test-data artifacts-req-opts))

    (def pipelines-resp (get-pipelines vcs organization project))

    (def items (get (get pipelines-resp :body) :items))
    (def workflows
      (mapcat (fn [item] (get-workflows (get item :id))) items))

    (def jobs
      (mapcat (fn [workflow] (get-workflow-jobs (get workflow :id))) workflows))

    (def artifacts
      (filter (fn [artifact]
        (re-find #"test-splitting-orb\/.*\.xml" (get artifact :path)))
        (mapcat (fn [job] (get-job-artifacts (get job :job_number))) jobs)))

    (def test-xml-data
      (map (fn [artifact] (get-test-data (get artifact :url))) artifacts))

    (def test-data
      (mapcat parse-test-xml-data test-xml-data))

    (def filename-map
      (reduce add-to-filename-map {} test-data))

    (def time-totals
      (get-time-totals
        (clojure.string/split test-files #" ")
        filename-map))

    (def buckets
      (fill-buckets time-totals (into [] (take (edn/read-string node-total) (repeat []))) 0 (edn/read-string node-total)))

    (clojure.string/join " " (nth buckets (edn/read-string node-index)))))

(defn handler [request]
  (-> (response (page (get request :params)))
      (content-type "text/plain")))

(def app
  (-> handler wrap-params))


(defn -main [& args]
    (run-jetty app {:port 6789}))
