(defproject test-splitting-orb-agent "0.1.0"
  :description "Agent for test splitting orb"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main test-splitting-orb-agent.core
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [cheshire "5.1.0"]
                 [org.clojure/data.xml "0.2.0-alpha6"]
                 [org.clojure/data.zip "1.0.0"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-jetty-adapter "1.6.3"]
                 [clj-http "2.3.0"]]
  :repl-options {:init-ns test-splitting-orb-agent.core})
