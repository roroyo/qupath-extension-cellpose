plugins {
  id("qupath.extension-conventions")
  id("qupath.javafx-conventions")
  id("qupath.publishing-conventions")
  `java-library`
}

extra["moduleName"] = "qupath.extension.cellpose"
base {
  archivesName = "qupath-extension-cellpose"
  description = "QuPath extension prototype for running Cellpose from a local Python environment."
}
