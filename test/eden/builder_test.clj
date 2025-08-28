(ns eden.builder-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eden.builder :as builder]
            [eden.config :as config]
            [babashka.fs :as fs]))

(deftest test-calculate-page-path
  (testing "Clean URLs without file extensions"
    (let [ctx {:default-lang :no}]
      (is (= "/" (:path (#'builder/calculate-page-path ctx {:lang-code :no :is-index true}))))
      (is (= "/about" (:path (#'builder/calculate-page-path ctx {:lang-code :no :slug "about"}))))
      (is (= "/om-oss" (:path (#'builder/calculate-page-path ctx {:lang-code :no :slug "om-oss"}))))
      (is (= "/products" (:path (#'builder/calculate-page-path ctx {:lang-code :no :content-key :products}))))))

  (testing "Language prefix for non-default languages"
    (let [ctx {:default-lang :no}]
      (is (= "/en/about" (:path (#'builder/calculate-page-path ctx {:lang-code :en :slug "about"}))))
      (is (= "/en/products" (:path (#'builder/calculate-page-path ctx {:lang-code :en :slug "products"}))))))

  (testing "Index pages should have language prefix for non-default languages"
    (let [ctx {:default-lang :en}]
      ;; Default language index should be at root
      (is (= "/" (:path (#'builder/calculate-page-path ctx {:lang-code :en :is-index true :slug "home"})))
          "English (default) index should be at /")
      ;; Non-default language index should have language prefix
      (is (= "/no/" (:path (#'builder/calculate-page-path ctx {:lang-code :no :is-index true :slug "home"})))
          "Norwegian (non-default) index should be at /no/ not /")))

  (testing "Multiple languages with correct index page paths"
    (let [ctx {:default-lang :en}]
      ;; Test with multiple non-default languages
      (is (= "/de/" (:path (#'builder/calculate-page-path ctx {:lang-code :de :is-index true :slug "home"})))
          "German index should be at /de/")
      (is (= "/fr/" (:path (#'builder/calculate-page-path ctx {:lang-code :fr :is-index true :slug "home"})))
          "French index should be at /fr/")
      ;; Regular pages still work correctly
      (is (= "/de/about" (:path (#'builder/calculate-page-path ctx {:lang-code :de :is-index false :slug "about"})))
          "German about page should be at /de/about")))

  (testing "No .html extension in paths"
    (let [ctx {:default-lang :no}
          page {:lang-code :no :slug "test-page"}
          result (#'builder/calculate-page-path ctx page)]
      (is (not (str/includes? (:path result) ".html"))))))

(deftest test-write-output-with-strategies
  (let [temp-dir (str (fs/create-temp-dir))
        html-files [{:path "/about"
                     :html "<html>About</html>"
                     :lang-code :no
                     :content-key :about
                     :slug "om-oss"}
                    {:path "/products"
                     :html "<html>Products</html>"
                     :lang-code :no
                     :content-key :products
                     :slug "produkter"}]]

    (testing "Flat strategy creates .html files"
      (let [flat-dir (io/file temp-dir "flat")
            strategy-fn (config/parse-url-strategy :flat)]
        (builder/write-output html-files flat-dir strategy-fn)
        (is (.exists (io/file flat-dir "about.html")))
        (is (.exists (io/file flat-dir "products.html")))
        (is (= "<html>About</html>" (slurp (io/file flat-dir "about.html"))))
        (is (= "<html>Products</html>" (slurp (io/file flat-dir "products.html"))))))

    (testing "Nested strategy creates directory structure"
      (let [nested-dir (io/file temp-dir "nested")
            strategy-fn (config/parse-url-strategy :nested)]
        (builder/write-output html-files nested-dir strategy-fn)
        (is (.exists (io/file nested-dir "about/index.html")))
        (is (.exists (io/file nested-dir "products/index.html")))
        (is (= "<html>About</html>" (slurp (io/file nested-dir "about/index.html"))))
        (is (= "<html>Products</html>" (slurp (io/file nested-dir "products/index.html"))))))

    (testing "Custom strategy"
      (let [custom-dir (io/file temp-dir "custom")
            strategy-fn (fn [{:keys [path page]}]
                          (str "pages" path "-" (:slug page) ".html"))]
        (builder/write-output html-files custom-dir strategy-fn)
        (is (.exists (io/file custom-dir "pages/about-om-oss.html")))
        (is (.exists (io/file custom-dir "pages/products-produkter.html")))))

    (fs/delete-tree temp-dir)))

(deftest test-build-page-registry
  (testing "build-page-registry preserves all content metadata"
    (let [pages [{:content-key :products.fishctrl
                  :lang-code :en
                  :content {:slug "fishctrl"
                            :title "FishCtrl"
                            :type :product
                            :category "Anteo Fiskehelse"
                            :published true
                            :tagline "Complete fish welfare system"}}
                 {:content-key :products.logifish
                  :lang-code :en
                  :content {:slug "logifish"
                            :title "Logifish"
                            :type :product
                            :category "Anteo Logistikk"
                            :published true
                            :featured true}}
                 {:content-key :news.article1
                  :lang-code :en
                  :content {:slug "article-1"
                            :title "News Article"
                            :type :news
                            :date "2024-01-15"
                            :published false}}
                 {:content-key :about
                  :lang-code :en
                  :content {:slug "about"
                            :title "About Us"
                            :type :page}}]
          registry (builder/build-page-registry pages)]

      ;; Check that all pages are in registry under their language
      (is (= 4 (count (get registry :en))))

      ;; Check that all metadata is preserved for products
      (is (= {:slug "fishctrl"
              :title "FishCtrl"
              :type :product
              :category "Anteo Fiskehelse"
              :published true
              :tagline "Complete fish welfare system"}
             (get-in registry [:en :products.fishctrl])))

      (is (= {:slug "logifish"
              :title "Logifish"
              :type :product
              :category "Anteo Logistikk"
              :published true
              :featured true}
             (get-in registry [:en :products.logifish])))

      ;; Check news article metadata
      (is (= {:slug "article-1"
              :title "News Article"
              :type :news
              :date "2024-01-15"
              :published false}
             (get-in registry [:en :news.article1])))

      ;; Check simple page
      (is (= {:slug "about"
              :title "About Us"
              :type :page}
             (get-in registry [:en :about])))))

  (testing "build-page-registry handles missing required fields"
    (let [pages [{:content-key :page1
                  :lang-code :en
                  :content {:title "Page 1"}} ; missing slug
                 {:content-key :page2
                  :lang-code :en
                  :content {:slug "page-2"}} ; missing title
                 {:content-key :page3
                  :lang-code :en
                  :content {:slug "page-3"
                            :title "Page 3"
                            :type :page}}]
          registry (builder/build-page-registry pages)]

      ;; Pages with missing required fields should be excluded
      (is (= 1 (count (get registry :en))))
      (is (contains? (get registry :en) :page3))
      (is (not (contains? (get registry :en) :page1)))
      (is (not (contains? (get registry :en) :page2)))))

  (testing "build-page-registry preserves nested metadata"
    (let [pages [{:content-key :product1
                  :lang-code :en
                  :content {:slug "product-1"
                            :title "Product 1"
                            :metadata {:author "John Doe"
                                       :tags [:clojure :web]}
                            :settings {:enabled true
                                       :priority 10}}}]
          registry (builder/build-page-registry pages)]

      (is (= {:slug "product-1"
              :title "Product 1"
              :metadata {:author "John Doe"
                         :tags [:clojure :web]}
              :settings {:enabled true
                         :priority 10}}
             (get-in registry [:en :product1]))
          "Should preserve nested maps and collections")))

  (testing "build-page-registry handles multiple languages"
    (let [pages [{:content-key :home
                  :lang-code :en
                  :content {:slug "" :title "Welcome"}}
                 {:content-key :home
                  :lang-code :no
                  :content {:slug "" :title "Velkommen"}}
                 {:content-key :about
                  :lang-code :en
                  :content {:slug "about" :title "About Us"}}
                 {:content-key :about
                  :lang-code :no
                  :content {:slug "om" :title "Om Oss"}}]
          registry (builder/build-page-registry pages)]

      ;; Check structure is nested by language
      (is (= #{:en :no} (set (keys registry))))

      ;; Check English pages
      (is (= "Welcome" (get-in registry [:en :home :title])))
      (is (= "About Us" (get-in registry [:en :about :title])))

      ;; Check Norwegian pages
      (is (= "Velkommen" (get-in registry [:no :home :title])))
      (is (= "Om Oss" (get-in registry [:no :about :title]))))))
