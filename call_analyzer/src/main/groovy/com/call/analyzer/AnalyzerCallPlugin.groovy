package com.call.analyzer

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AnalyzerCallPlugin implements Plugin<Project> {


    AnalyzerCallExtension callAnalyzerExtension


    @Override
    void apply(Project project) {

        callAnalyzerExtension = project.extensions.create("callAnalyzer", AnalyzerCallExtension)

        project.afterEvaluate {
            def extension = project.android
            def variants
            if (extension instanceof LibraryExtension) {
                variants = extension.libraryVariants
            } else if (extension instanceof AppExtension) {
                variants = extension.applicationVariants
            } else {
                throw new RuntimeException("not support")
            }

            variants.all { variant ->
                String variantName = variant.name.capitalize()
                def analyzeTask = project.tasks.register(String.format("analyzer%sCalls", variantName), AnalyzerCallTask)

                def assembleTask = project.tasks.named(String.format("assemble%s", variantName))

                def javaCompileTask = variant.javaCompileProvider.get()

                analyzeTask.configure { task ->
                    task.outputs.upToDateWhen { false }
                    task.dependsOn(assembleTask)
                    task.classesDir.from(javaCompileTask.outputs.files.collect().toArray())
                    AnalyzerCallTask.ExcludeConfig excludeConfig = new AnalyzerCallTask.ExcludeConfig();
                    excludeConfig.excludePackage = callAnalyzerExtension.excludePackage
                    excludeConfig.excludePrefix = callAnalyzerExtension.excludePrefix
                    task.exclude.set(excludeConfig)
                    task.variant.set(variantName)
                    task.reportDirectory.set(project.file(project.buildDir.absolutePath + File.separator + "reports"))
                }
            }
        }
    }
}
