(ns eden.loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eden.loader :as loader]
            [clojure.java.io :as io]
            [babashka.fs :as fs]))

(deftest test-path-to-content-key
  (testing "converts file paths to content keywords"
    (is (= :landing
           (#'loader/path-to-content-key "landing.md")))
    (is (= :about
           (#'loader/path-to-content-key "about.edn")))
    (is (= :products.logistics
           (#'loader/path-to-content-key "products/logistics.md")))
    (is (= :products.fish-health
           (#'loader/path-to-content-key "products/fish-health.edn")))
    (is (= :news.2024-04-15-partnerskap
           (#'loader/path-to-content-key "news/2024-04-15-partnerskap.md")))
    (is (= :deep.nested.path.to.content
           (#'loader/path-to-content-key "deep/nested/path/to/content.md"))))

  (testing "handles various file extensions"
    (is (= :page
           (#'loader/path-to-content-key "page.md")))
    (is (= :page
           (#'loader/path-to-content-key "page.edn")))
    (is (= :page
           (#'loader/path-to-content-key "page.markdown"))))

  (testing "handles edge cases"
    (is (= :page
           (#'loader/path-to-content-key "page")))
    (is (nil?
         (#'loader/path-to-content-key "")))
    (is (nil?
         (#'loader/path-to-content-key nil)))))

(deftest test-parse-markdown
  (testing "EDN frontmatter format"
    (testing "valid EDN with all data types"
      (let [content "{:slug \"products\"
 :template :page
 :title \"Products Page\"
 :published true
 :priority 10
 :tags [\"tech\" \"marine\"]}
---
# Product Content

This is the main content."
            result (#'loader/parse-markdown content)]
        (is (= "products" (:slug result)))
        (is (= :page (:template result)))
        (is (= "Products Page" (:title result)))
        (is (= true (:published result)))
        (is (= 10 (:priority result)))
        (is (= ["tech" "marine"] (:tags result)))
        (is (str/includes? (:markdown/content result) "# Product Content"))))

    (testing "EDN with nested maps"
      (let [content "{:slug \"test\"
 :meta {:author \"John\"
        :date \"2024-01-15\"}}
---
Content here"
            result (#'loader/parse-markdown content)]
        (is (= {:author "John" :date "2024-01-15"} (:meta result)))
        (is (= "Content here" (str/trim (:markdown/content result))))))

    (testing "invalid EDN - malformed"
      (let [content "{:slug \"test\" invalid}
---
Content"
            result (#'loader/parse-markdown content)]
        (is (contains? result :eden/parse-warning))
        (is (str/includes? (:eden/parse-warning result) "Invalid EDN"))
        (is (str/includes? (:markdown/content result) "{:slug"))))

    (testing "EDN not a map - returns warning"
      (let [content "[1 2 3]
---
Content"
            result (#'loader/parse-markdown content)]
        (is (contains? result :eden/parse-warning))
        (is (str/includes? (:eden/parse-warning result) "must be a map"))
        (is (str/includes? (:markdown/content result) "[1 2 3]"))))

    (testing "EDN without --- divider"
      (let [content "{:slug \"test\"}
No divider here"
            result (#'loader/parse-markdown content)]
        (is (not (contains? result :slug)))
        (is (str/includes? (:markdown/content result) "{:slug"))))

    (testing "empty EDN map"
      (let [content "{}
---
Just content"
            result (#'loader/parse-markdown content)]
        (is (= {} (dissoc result :markdown/content)))
        (is (= "Just content" (str/trim (:markdown/content result)))))))

  (testing "legacy key:value format (backward compatibility)"
    (testing "basic metadata"
      (let [content "slug: om-oss
title: Om Oss
template: page

# About Us"
            result (#'loader/parse-markdown content)]
        (is (= "om-oss" (:slug result)))
        (is (= "Om Oss" (:title result)))
        (is (= "page" (:template result)))
        (is (str/includes? (:markdown/content result) "# About Us"))))

    (testing "metadata with spaces in values"
      (let [content "title: This is a long title
slug: test-page

Content"
            result (#'loader/parse-markdown content)]
        (is (= "This is a long title" (:title result)))
        (is (= "test-page" (:slug result)))))

    (testing "no blank line after metadata"
      (let [content "slug: test
title: Test
Content starts here"
            result (#'loader/parse-markdown content)]
        (is (= "test" (:slug result)))
        (is (= "Test" (:title result)))
        (is (= "Content starts here" (:markdown/content result))))))

  (testing "no metadata formats"
    (testing "plain markdown content"
      (let [content "# Just a heading

Regular markdown content"
            result (#'loader/parse-markdown content)]
        (is (= {:markdown/content content} result))))

    (testing "content that looks like metadata but isn't at start"
      (let [content "Some content first

key: value
another: thing"
            result (#'loader/parse-markdown content)]
        (is (= {:markdown/content content} result))))

    (testing "empty content"
      (let [result (#'loader/parse-markdown "")]
        (is (= {:markdown/content ""} result))))

    (testing "whitespace only"
      (let [result (#'loader/parse-markdown "   \n  \t  ")]
        (is (= {:markdown/content ""} result))))))

(deftest test-markdown-to-html-conversion
  (testing "Markdown content is converted to HTML during loading"
    (let [temp-dir (fs/create-temp-dir)
          content-dir (fs/create-dirs (fs/path temp-dir "content" "en"))]

      ;; Create a markdown file with content
      (spit (str (fs/path content-dir "test.md"))
            "{:title \"Test Page\"
 :slug \"test\"}
---
# Test Heading

This is a **bold** paragraph with *italic* text.")

      ;; Load the content
      (let [loaded-content (#'loader/load-all-content-files (io/file (str temp-dir)))
            test-content (get-in loaded-content [:en :test])]

        ;; Verify markdown was converted to HTML
        (is (contains? test-content :content/html)
            "Content should have :content/html field")
        (is (not (contains? test-content :markdown/content))
            "Raw markdown should be removed after conversion")

        ;; Check HTML content
        (is (str/includes? (:content/html test-content) "<h1>Test Heading</h1>")
            "Heading should be converted to HTML")
        (is (str/includes? (:content/html test-content) "<strong>bold</strong>")
            "Bold markdown should become <strong> tags")
        (is (str/includes? (:content/html test-content) "<em>italic</em>")
            "Italic markdown should become <em> tags")

        ;; Verify metadata is preserved
        (is (= "Test Page" (:title test-content)))
        (is (= "test" (:slug test-content)))
        (is (= :test (:content-key test-content))))

      ;; Clean up
      (fs/delete-tree temp-dir)))

  (testing "EDN files don't get HTML conversion"
    (let [temp-dir (fs/create-temp-dir)
          content-dir (fs/create-dirs (fs/path temp-dir "content" "en"))]

      ;; Create an EDN file
      (spit (str (fs/path content-dir "data.edn"))
            "{:title \"Data File\"
 :some-data [1 2 3]}")

      ;; Load the content
      (let [loaded-content (#'loader/load-all-content-files (io/file (str temp-dir)))
            data-content (get-in loaded-content [:en :data])]

        ;; Verify EDN files don't get HTML field
        (is (not (contains? data-content :content/html))
            "EDN files should not have :content/html")
        (is (not (contains? data-content :markdown/content))
            "EDN files should not have :markdown/content")
        (is (= [1 2 3] (:some-data data-content))
            "EDN data should be preserved as-is"))

      ;; Clean up
      (fs/delete-tree temp-dir))))

(deftest test-content-key-addition
  (testing "all loaded content should have :content-key field"
    (let [temp-dir (fs/create-temp-dir)
          site-dir (str temp-dir)]
      (try
        ;; Create site structure
        (fs/create-dirs (fs/path site-dir "content" "no" "products"))
        (fs/create-dirs (fs/path site-dir "content" "en"))
        (fs/create-dirs (fs/path site-dir "templates"))

        ;; Create site.edn
        (spit (fs/file site-dir "site.edn")
              {:render #{:landing :products.widget}})

        ;; Create content files
        (spit (fs/file site-dir "content/no/landing.edn")
              {:title "Forsiden"
               :slug "/"})

        (spit (fs/file site-dir "content/no/about.md")
              "{:slug \"om-oss\"
 :title \"Om Oss\"
 :template :page}
---
# About content")

        (spit (fs/file site-dir "content/no/products/widget.md")
              "{:slug \"produkter/widget\"
 :title \"Widget\"
 :type :product}
---
Widget description")

        (spit (fs/file site-dir "content/en/landing.edn")
              {:title "Home"
               :slug "/"})

        ;; Create a template
        (spit (fs/file site-dir "templates/default.edn")
              [:div])

        ;; Load site data
        (let [result (loader/load-site-data (str site-dir "/site.edn") "dist")
              no-content (get-in result [:content :no])
              en-content (get-in result [:content :en])]

          ;; Test Norwegian content has :content-key
          (is (= :landing (get-in no-content [:landing :content-key]))
              "Landing page should have :content-key = :landing")

          (is (= :about (get-in no-content [:about :content-key]))
              "About page should have :content-key = :about")

          (is (= :products.widget (get-in no-content [:products.widget :content-key]))
              "Product widget should have :content-key = :products.widget")

          ;; Test English content has :content-key
          (is (= :landing (get-in en-content [:landing :content-key]))
              "English landing should have :content-key = :landing")

          ;; Verify content-key is added alongside existing data
          (is (= "Forsiden" (get-in no-content [:landing :title]))
              "Original content should be preserved")

          (is (= "Widget" (get-in no-content [:products.widget :title]))
              "Original markdown metadata should be preserved"))

        (finally
          (fs/delete-tree temp-dir))))))

(deftest test-load-site-data
  (testing "loads all content files into flat structure organized by language"
    (let [temp-dir (fs/create-temp-dir)
          site-dir (str temp-dir)]
      (try
        ;; Create site structure
        (fs/create-dirs (fs/path site-dir "content" "no" "products"))
        (fs/create-dirs (fs/path site-dir "content" "no" "news"))
        (fs/create-dirs (fs/path site-dir "content" "en"))
        (fs/create-dirs (fs/path site-dir "templates"))

        ;; Create site.edn
        (spit (fs/file site-dir "site.edn")
              {:render #{:landing :about :products.logistics}
               :lang {:no {:default true}
                      :en {}}})

        ;; Create Norwegian content
        (spit (fs/file site-dir "content/no/landing.edn")
              {:title "Forsiden"
               :slug "/"
               :products {:logistics {:description "Logistikk beskrivelse"}
                          :fish-health {:description "Fiskehelse beskrivelse"}}})

        (spit (fs/file site-dir "content/no/about.md")
              "slug: om-oss\ntitle: Om Oss\n\n# Om Anteo")

        (spit (fs/file site-dir "content/no/products/logistics.md")
              "slug: produkter/logistikk\ntitle: Anteo Logistikk\n\n# Logistics content")

        (spit (fs/file site-dir "content/no/products/fish-health.edn")
              {:slug "produkter/fiskehelse"
               :title "Anteo Fiskehelse"
               :description "Fish health systems"})

        (spit (fs/file site-dir "content/no/news/2024-article.md")
              "slug: nyheter/2024-article\ntitle: News Article\n\nContent here")

        ;; Create English content
        (spit (fs/file site-dir "content/en/landing.edn")
              {:title "Home"
               :slug "/"})

        (spit (fs/file site-dir "content/en/about.md")
              "slug: about\ntitle: About Us\n\n# About Anteo")

        ;; Create translation strings (should be ignored)
        (spit (fs/file site-dir "content/strings.no.edn")
              {:common/read-more "Les mer"})

        ;; Create a template
        (spit (fs/file site-dir "templates/landing.edn")
              [:div [:h1 [:eden/get :title]]])

        ;; Load site data
        (let [result (loader/load-site-data (str site-dir "/site.edn") "dist")]

          ;; Test structure exists
          (is (contains? result :content))
          (is (contains? result :templates))
          (is (contains? result :config))

          ;; Test content organization by language
          (is (contains? (:content result) :no))
          (is (contains? (:content result) :en))

          ;; Test Norwegian content keys are flat with dots
          (is (= #{:landing :about :products.logistics :products.fish-health :news.2024-article}
                 (set (keys (:no (:content result))))))

          ;; Test English content keys
          (is (= #{:landing :about}
                 (set (keys (:en (:content result))))))

          ;; Test content data is loaded correctly
          (is (= "Forsiden"
                 (get-in result [:content :no :landing :title])))
          (is (= "Om Oss"
                 (get-in result [:content :no :about :title])))
          (is (= "Anteo Logistikk"
                 (get-in result [:content :no :products.logistics :title])))
          (is (= "Anteo Fiskehelse"
                 (get-in result [:content :no :products.fish-health :title])))

          ;; Test nested data in content is preserved
          (is (= {:logistics {:description "Logistikk beskrivelse"}
                  :fish-health {:description "Fiskehelse beskrivelse"}}
                 (get-in result [:content :no :landing :products])))

          ;; Test markdown parsing
          (is (contains? (get-in result [:content :no :about]) :content/html)
              "Markdown files should have :content/html after loading")

          ;; Test templates are loaded
          (is (contains? (:templates result) :landing))

          ;; Test strings files are NOT in content
          (is (not (contains? (:no (:content result)) :strings))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "handles missing content directory gracefully"
    (let [temp-dir (fs/create-temp-dir)
          site-dir (str temp-dir)]
      (try
        ;; Create minimal site without content dir
        (spit (fs/file site-dir "site.edn")
              {:render #{:landing}})
        (fs/create-dirs (fs/path site-dir "templates"))
        (spit (fs/file site-dir "templates/default.edn") [:div])

        (let [result (loader/load-site-data (str site-dir "/site.edn") "dist")]
          ;; Should work but content should be empty or nil
          (is (or (nil? (:content result))
                  (empty? (:content result)))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "handles deeply nested content structure"
    (let [temp-dir (fs/create-temp-dir)
          site-dir (str temp-dir)]
      (try
        ;; Create deep structure
        (fs/create-dirs (fs/path site-dir "content" "no" "products" "category" "subcategory"))
        (fs/create-dirs (fs/path site-dir "templates"))

        (spit (fs/file site-dir "site.edn") {})

        (spit (fs/file site-dir "content/no/products/category/subcategory/item.edn")
              {:title "Deep Item"
               :slug "deep/item"})

        (let [result (loader/load-site-data (str site-dir "/site.edn") "dist")]
          ;; Test deeply nested content gets flattened with dots
          (is (contains? (:no (:content result)) :products.category.subcategory.item))
          (is (= "Deep Item"
                 (get-in result [:content :no :products.category.subcategory.item :title]))))

        (finally
          (fs/delete-tree temp-dir))))))