package repo.build

import groovy.transform.CompileStatic
import org.apache.maven.shared.invoker.InvocationRequest
import org.apache.maven.shared.invoker.InvocationResult
import repo.build.maven.MavenArtifact
import repo.build.maven.MavenArtifactRef
import repo.build.maven.MavenComponent

/**
 */
class MavenFeature {
    static void updateParent(RepoEnv env, String featureBranch, String parentComponent) {
        def parentBranch = Git.getBranch(new File(env.basedir, parentComponent))
        if (featureBranch != parentBranch) {
            throw new RepoBuildException("parent component must switched to feature branch $featureBranch")
        }
        // get parent artifact
        def parentPomFile = new File(env.basedir, parentComponent + "/pom.xml")
        def parentPom = XmlUtils.parse(parentPomFile)
        def groupId = parentPom.groupId.text()
        def artifactId = parentPom.artifactId.text()
        def version = parentPom.version.text()

        // rebuild parent
        Maven.execute(parentPomFile,
                { InvocationRequest req ->
                    req.setGoals(Arrays.asList("clean", "install"))
                    req.setInteractive(false)
                    Properties properties = new Properties();
                    properties.put("skipTest", 'true')
                    req.setProperties(properties)
                }
        )

        // для всех компонентов в кторых ест фича бранч
        RepoManifest.forEachWithFeatureBranch(env, { project ->
            // пропускаем parent
            if (parentComponent != project.@path) {
                def dir = new File(env.basedir, project.@path)
                def componentPomFile = new File(dir, "/pom.xml")
                def componentPom = XmlUtils.parse(componentPomFile)
                def parentGroupId = componentPom?.parent?.groupId?.text()
                def parentArtifactId = componentPom?.parent?.artifactId?.text()
                def parentVersion = componentPom?.parent?.version?.text()
                if (groupId == parentGroupId
                        && artifactId == parentArtifactId
                        && version != parentVersion) {
                    // если группа, артефакт совпадают а версия нет - подменяем версию parent
                    Maven.execute(componentPomFile,
                            { InvocationRequest req ->
                                req.setGoals(Arrays.asList("versions:update-parent"))
                                req.setInteractive(false)
                                Properties properties = new Properties();
                                properties.put("parentVersion", version)
                                properties.put('generateBackupPoms', 'false')
                                properties.put('allowSnapshots', 'true')
                                req.setProperties(properties)
                            }
                    )
                    // check modify pom.xml
                    if (Git.isFileModified(dir, "pom.xml")) {
                        // if it modifies - commit vup
                        Git.add(dir, "pom.xml")
                        Git.commit(dir, "update parent to $parentVersion")
                    }
                }
            }
        }, featureBranch)
    }

    @CompileStatic
    static void updateVersions(RepoEnv env, String featureBranch, String includes) {
        Pom.generateXml(env, featureBranch, new File(env.basedir, 'pom.xml'))

        // получаем компоненты и зависимости
        def componentsMap = getComponentsMap(env.basedir)
        // формируем граф зависимостей
        List<MavenComponent> sortedComponents = sortComponents(componentsMap)
        sortedComponents.each {
            // call version plugin
            Maven.execute(new File(it.basedir, "pom.xml"),
                    { InvocationRequest req ->
                        req.setGoals(Arrays.asList("versions:update-properties"))
                        req.setInteractive(false)
                        Properties properties = new Properties();
                        properties.put("allowSnapshots", "true")
                        properties.put("includes", includes)
                        properties.put('generateBackupPoms', 'false')
                        req.setProperties(properties)
                    }
            )
            // maven build with skipTests
            Maven.execute(new File(it.basedir, "pom.xml"),
                    { InvocationRequest req ->
                        req.setGoals(Arrays.asList("clean", "install"))
                        req.setInteractive(false)
                        Properties properties = new Properties();
                        properties.put("skipTest", 'true')
                        req.setProperties(properties)
                    }
            )
            // check modify pom.xml
            if (Git.isFileModified(it.basedir, "pom.xml")) {
                // if it modifies - commit vup
                Git.add(it.basedir, "pom.xml")
                Git.commit(it.basedir, "update dependencies to last feature snapshot")
            }
        }

    }

    @CompileStatic
    static List<MavenComponent> sortComponents(Map<MavenArtifactRef, MavenComponent> componentsMap) {
        def graph = ComponentDependencyGraph.build(componentsMap)
        return graph.sort()
    }

    @CompileStatic
    static Map<MavenArtifactRef, MavenComponent> getComponentsMap(File basedir) {
        List<MavenComponent> components = getComponents(basedir)
        Map<MavenArtifactRef, MavenComponent> result = new HashMap<>();
        for (MavenComponent c : components) {
            for (MavenArtifact m : c.getModules()) {
                // map all component modules into host component
                result.put(new MavenArtifactRef(m.getGroupId(), m.getArtifactId()), c)
            }
        }
        return result
    }

    static List<MavenComponent> getComponents(File basedir) {
        List<MavenComponent> result = new ArrayList<>()
        def pomFile = new File(basedir, "pom.xml")
        // собираем компонентыs
        Pom.getModules(pomFile).each {
            File componentBasedir = new File(basedir, it)
            def pom = new XmlParser().parse(new File(componentBasedir, 'pom.xml'))
            MavenComponent component = new MavenComponent()
            component.setBasedir(componentBasedir)
            component.setModules(getComponentModules(componentBasedir))
            component.setGroupId(pom.groupId.text())
            component.setArtifactId(pom.artifactId.text())
            result.add(component)
        }
        return result
    }

    static Set<MavenArtifact> getComponentModules(File basedir) {
        Set<MavenArtifact> result = new HashSet<>()
        def module = new MavenArtifact()
        def project = new XmlParser().parse(new File(basedir, 'pom.xml'))
        module.basedir = basedir
        module.setGroupId(project.groupId ? project.groupId.text() : project.parent.groupId.text())
        module.setArtifactId(project.artifactId.text())
        module.setDependencies(getProjectDependencies(project))
        if (project.parent) {
            module.getDependencies().add(
                    new MavenArtifactRef(
                            project.parent.groupId.text(),
                            project.parent.artifactId.text()))
        }
        result.add(module)
        Pom.getModules(new File(basedir, "pom.xml")).each {
            File moduleBasedir = new File(basedir, it)
            result.addAll(getComponentModules(moduleBasedir))
        }
        return result
    }

    static Set<MavenArtifactRef> getProjectDependencies(Node project) {
        Set<MavenArtifactRef> result = new HashSet<>()
        def parseDependencies = { Node dependencies ->
            dependencies.dependency.each {
                result.add(new MavenArtifactRef(it.groupId.text(), it.artifactId.text()))
            }
        }
        if (project.dependencyManagement.dependencies) {
            parseDependencies(project.dependencyManagement.dependencies)
        }
        if (project.dependencies) {
            parseDependencies(project.dependencies)
        }
        return result
    }
}
