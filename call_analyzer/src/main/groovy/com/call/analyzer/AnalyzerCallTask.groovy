package com.call.analyzer

import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.opencsv.CSVWriter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

import java.util.zip.ZipInputStream

class AnalyzerCallTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    final ConfigurableFileCollection classesDir = project.objects.fileCollection()

    @Input
    final Property<ExcludeConfig> exclude = project.objects.property(ExcludeConfig)


    @Input
    final MapProperty<String, String> classPathMap = project.objects.mapProperty(String, String)

    @Input
    final Property<String> variant = project.objects.property(String)


    @OutputDirectory
    final DirectoryProperty reportDirectory = getProject().getObjects().directoryProperty();

    private Map<String, String> getExternalClasses(Map<String, String> classPathMap) {
        Map<String, String> externalClassesMas = new HashMap<>()
        classPathMap.entrySet().forEach { map ->
            project.file(map.value).withInputStream { is ->
                is.withCloseable { iso ->
                    new ZipInputStream(is).withCloseable { zis ->
                        def entry = zis.nextEntry
                        while (entry) {
                            def entryName = entry.name

                            if (entryName.empty) {
                                entry = zis.nextEntry
                                continue
                            }
                            if (entryName.endsWith(".class")) {
                                String className = entryName.replace('\\', '.').replace('/', '.');
                                externalClassesMas.put(className, map.key)
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        }

        return externalClassesMas

    }

    @TaskAction
    void analyze() throws Exception {

        def classPathMap = project.configurations.named(String.format("%sCompileClasspath", variant.get().toLowerCase())).get().incoming.artifactView { vc ->
            vc.lenient = true
            vc.attributes { ac ->
                // 设置 android-classes-jar 这里会帮我们把aar，dir的依赖强制压缩为jar
                ac.attribute(AndroidArtifacts.ARTIFACT_TYPE, AndroidArtifacts.ArtifactType.CLASSES_JAR.type)
            }
        }.artifacts.artifacts.collectEntries { artifactResult ->
            [artifactResult.id.componentIdentifier.displayName, artifactResult.file.absolutePath]
        }


        if (classesDir.empty) {
            println("classesDirs is empty")
            return
        }

        Map<String, String> externalClassesMap = getExternalClasses(classPathMap)
        def excludeConfig = exclude.get()
        externalClassesMap.removeAll {
            for (final def prefix in excludeConfig.excludePrefix) {
                if (it.key.startsWith(prefix)) {
                    return true
                }
            }

            for (final def pkg in excludeConfig.excludePackage) {
                if(it.key.contains(pkg)) {
                    return true
                }
            }

            return false
        }

        if (externalClassesMap.isEmpty()) {
            println("external classes is empty")
        }


        List<CallSite> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        def index = 0
        def classesFiles = classesDir.asFileTree.files
        classesFiles.forEach { file ->
//            if (index < 20) {
            if (file.name.endsWith(".class")) {
                printf(String.format("[%d/%d]: analyzer class is %s%n", index, classesFiles.size(), file.absolutePath))
                analyzerClasses(file, calls, seen, externalClassesMap)
            }
            index++
//            }

        }

        generateReport(classPathMap, calls, externalClassesMap);
    }

    void analyzerClasses(File file, List<CallSite> calls, Set<String> seen, Map<String, String> externalClassesMap) {

        byte[] bytes = file.withInputStream {
            it.bytes
        }
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String callerClass = cn.name.replace('/', '.');

        for (MethodNode method : (List<MethodNode>) cn.methods) {
            if (method.instructions == null) continue;

            for (final def insn in method.instructions) {
                if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode minsn = (MethodInsnNode) insn;
                    String owner = minsn.owner.replace('/', '.');
                    // com.tencent.mm.sdk.openapi.WXAPIFactory

                    // 跳过 JDK / Android 内部类
                    if (owner.startsWith("java.") ||
                            owner.startsWith("javax.") ||
                            owner.startsWith("android.") ||
                            owner.startsWith("kotlin.")) {
                        continue;
                    }

                    // 匹配 SDK
                    for (Map.Entry<String, String> entry : externalClassesMap.entrySet()) {
                        def externalClassName = entry.getKey().replace(".class", "")
                        if (owner.contains(externalClassName)) {
                            String moduleName = entry.getValue();
                            String callerMethod = callerClass + "." + method.name + method.desc;
                            String targetMethod = owner + "." + minsn.name + minsn.desc;

                            String key = callerMethod + " -> " + targetMethod;
                            if (seen.add(key)) { // 去重
                                calls.add(new CallSite(moduleName, entry.getKey(), callerMethod, targetMethod));
                            }
                        }
                    }
                }
            }
        }
    }

    void generateReport(Map<String, String> classPathMap, List<CallSite> calls, Map<String, String> externalClassesMap) {

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()        // 格式化输出（带缩进和换行）
                .create();


        def dependencyJsonFile = reportDirectory.file("dependencies.json").get().getAsFile()
        // 转换为格式化的 JSON 字符串
        String dependencyJson = gson.toJson(classPathMap)

        dependencyJsonFile.withPrintWriter { pw ->
            pw.withCloseable {
                it.append(dependencyJson)
            }
        }


        def callsJsonFile = reportDirectory.file("calls.json").get().getAsFile()
        // 转换为格式化的 JSON 字符串
        String callsJson = gson.toJson(calls)

        callsJsonFile.withPrintWriter { pw ->
            pw.withCloseable {
                it.append(callsJson)
            }
        }

        def callsTextFile = reportDirectory.file("calls.text").get().getAsFile()

        Map<String, Map<String, List<String>>> outputTextMap = new HashMap<>()
        calls.forEach {
            def moduleMap = outputTextMap.get(it.moduleName)
            if (moduleMap == null) {
                moduleMap = new HashMap<String, List<String>>()
                outputTextMap.put(it.moduleName, moduleMap)
            }
            def callMethods = moduleMap.get(it.targetMethod)
            if (callMethods == null) {
                callMethods = new ArrayList<String>()
                moduleMap.put(it.targetMethod, callMethods)
            }
            callMethods.add(it.callerMethod)
        }

        callsTextFile.withPrintWriter { pw ->
            pw.withCloseable { out ->
                out.println("=== Analyzer Call Report ===")
                out.printf("Dependencies Scanned: %d%n", classPathMap.size())
                out.printf("Calls Scanned: %d%n", calls.size())
                out.printf("-------------------------------------------------------%n")

                classPathMap.entrySet().forEach { entry ->
                    out.printf("%s%n", entry.key)
                }

                out.printf("-------------------------------------------------------%n")

                outputTextMap.entrySet().forEach { moduleEntry ->
                    out.printf("%s%n", moduleEntry.key)
                    moduleEntry.value.entrySet().forEach { targetEntry ->
                        out.printf("    -> %s%n", targetEntry.key)
                        targetEntry.value.forEach { method ->
                            out.printf("        --> %s%n", method)
                        }
                    }
                    out.printf("%n")
                }
            }
        }

        def callsCSVFile = reportDirectory.file("calls.csv").get().getAsFile()


        List<String[]> csvList = new ArrayList<>();

        outputTextMap.entrySet().forEach {
            it.value.entrySet().forEach { entry ->
                StringBuilder stringBuilder = new StringBuilder()
                entry.value.forEach { value ->
                    def index = value.lastIndexOf(".")
                    def name = value.substring(index)
                    stringBuilder.append(name + "\n\n")
                }
                String[] line = new String[2]
                line[0] = entry.key
                line[1] = stringBuilder.toString()
                csvList.add(line)
            }
        }

        callsCSVFile.withWriter('UTF-8') { bw ->
            bw.withCloseable { writer ->
                def csvWriter = new CSVWriter(writer)
                csvWriter.writeAll(csvList)
                csvWriter.close()
            }
        }
    }

    // 数据结构
    static class CallSite {
        final String moduleName;
        final String className;
        final String callerMethod;
        final String targetMethod;

        CallSite(String moduleName, String className, String callerMethod, String targetMethod) {
            this.moduleName = moduleName;
            this.className = className;
            this.callerMethod = callerMethod;
            this.targetMethod = targetMethod;
        }
    }


    static class ExcludeConfig implements Serializable {
        public List<String> excludePackage;
        public List<String> excludePrefix;
    }
}