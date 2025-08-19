(ns eden.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [eden.config :as config]))

(deftest test-parse-url-strategy
  (testing "Flat strategy"
    (let [strategy-fn (config/parse-url-strategy :flat)]
      (is (= "about.html" (strategy-fn {:path "/about"})))
      (is (= "products.html" (strategy-fn {:path "/products"})))
      (is (= "products/logistics.html" (strategy-fn {:path "/products/logistics"})))))

  (testing "Nested strategy"
    (let [strategy-fn (config/parse-url-strategy :nested)]
      (is (= "index.html" (strategy-fn {:path "/"})))
      (is (= "about/index.html" (strategy-fn {:path "/about"})))
      (is (= "products/index.html" (strategy-fn {:path "/products"})))
      (is (= "products/logistics/index.html" (strategy-fn {:path "/products/logistics"})))))

  (testing "Unknown keyword strategy throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown url-strategy"
                          (config/parse-url-strategy :unknown))))

  (testing "Invalid strategy type throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid url-strategy"
                          (config/parse-url-strategy 123)))))

(deftest test-custom-url-strategy
  (testing "Custom function via list (SCI)"
    (let [strategy-fn (#'config/parse-url-strategy
                       '(fn [{:keys [path lang]}]
                          (if (= lang :en)
                            (str "en" path ".html")
                            (str (subs path 1) ".html"))))]
      (is (= "about.html" (strategy-fn {:path "/about" :lang :no})))
      (is (= "en/about.html" (strategy-fn {:path "/about" :lang :en})))))

  (testing "Custom function with page data"
    (let [strategy-fn (#'config/parse-url-strategy
                       '(fn [{:keys [path page]}]
                          (let [slug (:slug page)]
                            (if (= slug "landing")
                              "index.html"
                              (str slug ".html")))))]
      (is (= "index.html" (strategy-fn {:path "/" :page {:slug "landing"}})))
      (is (= "om-oss.html" (strategy-fn {:path "/about" :page {:slug "om-oss"}}))))))

(deftest test-page-url-strategies
  (testing "Default page URL strategy"
    (let [strategy (#'config/parse-page-url-strategy :default)]
      (is (= "/produkter"
             (strategy {:slug "produkter" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/products"
             (strategy {:slug "products" :lang :en
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/"
             (strategy {:slug "" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/"
             (strategy {:slug "" :lang :en
                        :site-config {:lang {:no {:default true}}}})))))

  (testing "Custom page URL strategy via function"
    (let [strategy (#'config/parse-page-url-strategy
                    '(fn [{:keys [slug lang]}]
                       (str "/" (name lang) "-" slug)))]
      (is (= "/no-produkter" (strategy {:slug "produkter" :lang :no})))
      (is (= "/en-products" (strategy {:slug "products" :lang :en})))))

  (testing "Custom page URL strategy with subdomain pattern"
    (let [strategy (#'config/parse-page-url-strategy
                    '(fn [{:keys [slug lang]}]
                       (if (= lang :no)
                         (str "/" slug)
                         (str "https://" (name lang) ".example.com/" slug))))]
      (is (= "/produkter" (strategy {:slug "produkter" :lang :no})))
      (is (= "https://en.example.com/products"
             (strategy {:slug "products" :lang :en})))))

  (testing "Page URL strategy handles nested paths"
    (let [strategy (#'config/parse-page-url-strategy :default)]
      (is (= "/products/logistics"
             (strategy {:slug "products/logistics" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/products/logistics"
             (strategy {:slug "products/logistics" :lang :en
                        :site-config {:lang {:no {:default true}}}})))))

  (testing "With-extension page URL strategy"
    (let [strategy (#'config/parse-page-url-strategy :with-extension)]
      (is (= "/produkter.html"
             (strategy {:slug "produkter" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/products.html"
             (strategy {:slug "products" :lang :en
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/index.html"
             (strategy {:slug "" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/index.html"
             (strategy {:slug "" :lang :en
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/products/logistics.html"
             (strategy {:slug "products/logistics" :lang :no
                        :site-config {:lang {:no {:default true}}}})))
      (is (= "/en/products/logistics.html"
             (strategy {:slug "products/logistics" :lang :en
                        :site-config {:lang {:no {:default true}}}}))))))
