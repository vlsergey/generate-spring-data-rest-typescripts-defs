package com.github.vlsergey.springdatarest2typescript;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

public class PluginImpl implements Plugin<Project> {

    @Override
    public void apply(Project project) {
	final PluginExtensionImpl ownConfig = project.getExtensions().create("springdatarest2typescript",
		PluginExtensionImpl.class);

	project.task("springdatarest2typescript", (Task task) -> {
	    task.dependsOn(":compileJava");
	    task.doLast(new ActionImpl(project, ownConfig));
	});
    }
}
