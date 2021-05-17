package com.github.vlsergey.springdatarest2typescript;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.function.Consumer;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.joining;

class ActionImpl implements Action<Task> {

    private static final String ANNOTATION_ENTITY = "javax.persistence.Entity";

    private static final String ANNOTATION_PROJECTION = "org.springframework.data.rest.core.config.Projection";

    private static final int INDENT = 2;

    private static final Logger log = LoggerFactory.getLogger(ActionImpl.class);

    private static final Map<Class<?>, String> standardClassesToTypeScript = new HashMap<>();

    private static final String TS_NUMBER = "number";
    private static final String TS_STRING = "string";

    static {
	standardClassesToTypeScript.put(Number.class, TS_NUMBER);
	standardClassesToTypeScript.put(String.class, TS_STRING);
	standardClassesToTypeScript.put(Temporal.class, TS_STRING);
	standardClassesToTypeScript.put(URI.class, TS_STRING);
	standardClassesToTypeScript.put(URL.class, TS_STRING);
	standardClassesToTypeScript.put(UUID.class, TS_STRING);

	standardClassesToTypeScript.put(byte.class, TS_STRING);
	standardClassesToTypeScript.put(double.class, TS_STRING);
	standardClassesToTypeScript.put(int.class, TS_STRING);
	standardClassesToTypeScript.put(long.class, TS_STRING);
	standardClassesToTypeScript.put(short.class, TS_STRING);
    }

    private final PluginExtensionImpl ownConfig;
    private final Project project;

    public ActionImpl(Project project, PluginExtensionImpl ownConfig) {
	this.project = project;
	this.ownConfig = ownConfig;
    }

    private static void generateTypeScriptPropertyDef(final Set<Class<?>> allEntities, final PropertyDescriptor pd,
	    final PrintStream result, final int indent) throws IntrospectionException {

	Optional<String> stdType = standardClassesToTypeScript.entrySet().stream()
		.filter(entry -> entry.getKey().isAssignableFrom(pd.getPropertyType())).map(Map.Entry::getValue)
		.findAny();

	indent(result, indent);
	result.print(pd.getName());
	result.print(": ");

	if (stdType.isPresent()) {
	    result.print(stdType.get());
	    result.println(";");
	    return;
	}

	if (pd.getPropertyType().isEnum()) {
	    result.print(Arrays.stream(pd.getPropertyType().getEnumConstants()).map(x -> (Enum<?>) x).map(Enum::name)
		    .map(x -> "'" + x + "'").collect(joining(" | ")));
	    result.println(";");
	    return;
	}

	// complex type
	result.println("{");

	BeanInfo bi = Introspector.getBeanInfo(pd.getPropertyType());
	for (PropertyDescriptor childPd : bi.getPropertyDescriptors()) {
	    if (childPd.getReadMethod().getDeclaringClass().getName().startsWith("java.lang.")) {
		continue;
	    }

	    if (allEntities.contains(childPd.getPropertyType())
		    || Collection.class.isAssignableFrom(childPd.getPropertyType())) {
		// ignored on 2+ level
		continue;
	    }

	    generateTypeScriptPropertyDef(allEntities, childPd, result, indent + INDENT);
	}

	indent(result, indent);
	result.println("};");
    }

    private static void indent(final PrintStream result, final int indent) {
	for (int i = 0; i < indent; i++) {
	    result.print(' ');
	}
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Annotation>> loadAnnotationClass(ClassLoader classLoader,
	    String className) {
	try {
	    return Optional.of((Class<? extends Annotation>) Class.forName(className, false, classLoader));
	} catch (Exception exc) {
	    log.warn("Missing annotation '" + className + "' in classpath. "
		    + "Entities with this annotation will not be found during lookup.");
	    return Optional.empty();
	}
    }

    @Override
    public void execute(Task taskImpl) {
	final List<URL> urls = new ArrayList<>();

	SourceSetContainer sourceSetContainer = project.getConvention().getByType(SourceSetContainer.class);
	sourceSetContainer.getAsMap().forEach((String name, SourceSet set) -> {
	    final Consumer<? super File> addToClassPath = file -> {
		try {
		    final URL url = file.toURI().toURL();
		    urls.add(url);
		    log.debug("Added to classPath: {}", url);
		} catch (MalformedURLException e) {
		    e.printStackTrace();
		}
	    };

	    set.getCompileClasspath().forEach(addToClassPath);
	    set.getOutput().getClassesDirs().forEach(addToClassPath);
	});

	try (URLClassLoader urlClassLoader = new URLClassLoader(urls.toArray(URL[]::new),
		Thread.currentThread().getContextClassLoader())) {

	    final Optional<Class<? extends Annotation>> entityAnnotation = loadAnnotationClass(urlClassLoader,
		    ANNOTATION_ENTITY);
	    final Optional<Class<? extends Annotation>> projectionAnnotation = loadAnnotationClass(urlClassLoader,
		    ANNOTATION_PROJECTION);

	    final String basePackage = this.ownConfig.getBasePackage().get();
	    final Reflections reflections = basePackage == null
		    ? new Reflections(urlClassLoader, new SubTypesScanner(true), new TypeAnnotationsScanner())
		    : new Reflections(basePackage, urlClassLoader, new SubTypesScanner(true),
			    new TypeAnnotationsScanner());

	    Set<Class<?>> candidates = new TreeSet<>(Comparator.comparing(Class::getSimpleName));
	    entityAnnotation.ifPresent(cls -> candidates.addAll(reflections.getTypesAnnotatedWith(cls)));
	    projectionAnnotation.ifPresent(cls -> candidates.addAll(reflections.getTypesAnnotatedWith(cls)));

	    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    PrintStream result = new PrintStream(baos, true, StandardCharsets.UTF_8);

	    result.println("interface " + this.ownConfig.getLinkTypeName().get() + " {");
	    result.println("  href: string;");
	    result.println("}");
	    result.println();

	    for (Class<?> cls : candidates) {
		log.debug("Checking class: {}", cls.getName());

		try {
		    final boolean isProjection = projectionAnnotation.map(cls::isAnnotationPresent).orElse(false);
		    generateTypeScriptInterface(cls, isProjection, candidates, result);
		} catch (Exception exc) {
		    log.error("Unable to generate info for class " + cls.getName() + ": " + exc.getMessage(), exc);
		}
	    }

	    final byte[] resultByteArray = baos.toByteArray();

	    final RegularFile output = ownConfig.getOutput().get();
	    Files.write(output.getAsFile().toPath(), resultByteArray, StandardOpenOption.WRITE,
		    StandardOpenOption.TRUNCATE_EXISTING);

	    log.info("Result ({} bytes) is written into {}", resultByteArray.length, output.getAsFile().getPath());
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    private void generateTypeScriptInterface(final Class<?> cls, final boolean isProjection,
	    final Set<Class<?>> otherCandidates, final PrintStream result) throws IntrospectionException {

	final String simpleName = cls.getSimpleName();
	result.println("interface " + simpleName + this.ownConfig.getTypeSuffix().get() + " {");

	Set<String> links = new TreeSet<>();
	links.add("self");
	// TODO: add second self link!

	final BeanInfo bi = Introspector.getBeanInfo(cls);
	for (PropertyDescriptor pd : bi.getPropertyDescriptors()) {
	    if (pd.getReadMethod().getDeclaringClass().getName().startsWith("java.lang.")) {
		continue;
	    }

	    boolean addToLinks = otherCandidates.contains(pd.getPropertyType())
		    || Collection.class.isAssignableFrom(pd.getPropertyType());
	    if (addToLinks) {
		links.add(pd.getName());

		if (!isProjection) {
		    continue;
		}
	    }

	    generateTypeScriptPropertyDef(otherCandidates, pd, result, INDENT);
	}

	if (!isProjection) {
	    result.println("  _links: {");
	    for (String link : links) {
		result.println("    " + link + ": " + this.ownConfig.getLinkTypeName().get() + ";");
	    }
	    result.println("  };");
	}

	result.println("}");
	result.println();
    }
}