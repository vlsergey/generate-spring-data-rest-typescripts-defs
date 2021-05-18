package com.github.vlsergey.springdatarest2typescript;

import java.io.File;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

abstract class PluginExtensionImpl {

    public PluginExtensionImpl() {
	getBasePackage().convention((String) null);
	getLinkTypeName().convention("LinkType");
	getOutput().convention(() -> new File("output.ts"));
	getTypeSuffix().convention("Type");
    }

    abstract Property<String> getBasePackage();

    abstract Property<String> getLinkTypeName();

    abstract RegularFileProperty getOutput();

    abstract Property<String> getTypeSuffix();

    public enum EnumMode {
	BUILTIN, SEPARATELY,;
    }
}
