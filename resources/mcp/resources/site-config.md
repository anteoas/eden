{:name "Eden Site Configuration"
 :description "Guide to configuring site.edn and build options"
 :url "eden://site-config"
 :mime-type "text/markdown"}
---
# Eden Site Configuration

## site.edn Structure
```clojure
{:site-title "My Eden Site"
 :base-url "https://example.com"
 :languages [:en :es]  ; Supported languages
 :default-language :en
 :output-dir "dist"   ; Build output directory
 :theme {:primary-color "#3B82F6"
         :font-family "Inter, sans-serif"}
 :plugins ["eden-sitemap" "eden-rss"]
 :image-processing
 {:sizes {:thumbnail [150 150]
          :hero [1920 1080]
          :card [600 400]}
  :formats [:webp :jpg]
  :quality 85}}
```

## Build Commands
```clojure
;; From REPL
(eden.core/build :site-edn "site.edn" :mode :prod)
(eden.core/dev :site-edn "site.edn")  ; Dev server

;; From CLI
clj -Teden build
clj -Teden dev
clj -Teden clean
```

## Image Processing
Configure automatic image optimization:
- Define size presets
- Set output formats (webp, jpg, png)
- Control quality settings
- Images in content automatically processed

## Development Mode
Dev mode features:
- Live reload with file watching
- Browser-sync integration
- Build performance metrics
- Warning reports