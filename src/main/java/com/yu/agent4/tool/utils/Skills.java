/*
* Copyright 2026 - 2026 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.yu.agent4.tool.utils;


import com.yu.agent4.tool.SkillsTool.Skill;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * @author Christian Tzolov
 */

public class Skills {

	/**
	 * 从给定的资源中加载技能，这些资源可以是包含 SKILL.md 文件的目录或 classpath 位置。
	 * @param skillsResources 要加载技能的资源列表
	 * @return 包含每个 SKILL.md 文件的 basePath、front-matter 和 content 的 Skill 对象列表
	 */
	public static List<Skill> loadResources(List<Resource> skillsResources) {
		List<Skill> skills = new ArrayList<>();
		for (Resource skillsResource : skillsResources) {
			skills.addAll(loadResource(skillsResource));
		}
		return skills;
	}

	/**
	 * 从给定的资源中加载技能。支持文件系统目录、基于 JAR 的 classpath 资源以及
	 * 引用 JAR 内部目录的 {@link ClassPathResource}。
	 * @param skillsResources 要加载技能的资源
	 * @return Skill 对象列表
	 * @throws RuntimeException 如果读取资源时发生 I/O 错误
	 */
	public static List<Skill> loadResource(Resource... skillsResources) {

		List<Skill> skills = new ArrayList<>();

		for (Resource skillsResource : skillsResources) {
			try {
				String path = skillsResource.getFile().toPath().toAbsolutePath().toString();
				skills.addAll(loadDirectory(path));
			}
			catch (IOException ex) {
				try {
					skills.addAll(loadJarResource(skillsResource));
				}
				catch (IOException jarEx) {
					throw new RuntimeException("Failed to load skills from resource: " + skillsResource, jarEx);
				}
			}
		}
		return skills;
	}

	public static List<Skill> loadDirectories(List<String> rootDirectories) {
		List<Skill> skills = new ArrayList<>();
		for (String rootDirectory : rootDirectories) {
			skills.addAll(loadDirectory(rootDirectory));
		}
		return skills;
	}

	/**
	 * 递归查找给定根目录下所有 SKILL.md 文件，并返回其解析后的内容。
	 * @param rootDirectory 要搜索 SKILL.md 文件的根目录
	 * @return 包含每个 SKILL.md 文件的 basePath、front-matter 和 content 的 Skill 对象列表
	 * @throws RuntimeException 如果读取目录或文件时发生 I/O 错误
	 */
	public static List<Skill> loadDirectory(String rootDirectory) {

		Path rootPath = Paths.get(rootDirectory);

		if (!Files.exists(rootPath)) {
			throw new RuntimeException("Root directory does not exist: " + rootDirectory);
		}

		if (!Files.isDirectory(rootPath)) {
			throw new RuntimeException("Path is not a directory: " + rootDirectory);
		}

		List<Skill> skills = new ArrayList<>();

		try (Stream<Path> paths = Files.walk(rootPath)) {
			paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().equals("SKILL.md"))
				.forEach(path -> {
					try {
						String markdown = Files.readString(path, StandardCharsets.UTF_8);
						MarkdownParser parser = new MarkdownParser(markdown);
						skills.add(new Skill(path.getParent().toString(), parser.getFrontMatter(),
								parser.getContent()));
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read SKILL.md file: " + path, e);
					}
				});
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to walk root directory: " + rootDirectory, e);
		}

		return skills;
	}

	/**
	 * 从非文件系统资源加载技能。处理两种情况：
	 * <ul>
	 * <li>具有可解析的 {@code jar:} URL 的资源（例如 {@link org.springframework.core.io.UrlResource}）— 使用 {@link JarURLConnection}</li>
	 * <li>{@link ClassPathResource}，但目录缺少显式 JAR 条目 — 使用 Spring 的 {@link ResourcePatternResolver}，并降级为手动 JAR 扫描</li>
	 * </ul>
	 * @param resource 指向技能目录的资源
	 * @return 从 SKILL.md 文件解析出的 Skill 对象列表
	 * @throws IOException 如果读取时发生 I/O 错误
	 */
	private static List<Skill> loadJarResource(Resource resource) throws IOException {
		URL resourceUrl;
		try {
			resourceUrl = resource.getURL();
		}
		catch (FileNotFoundException ex) {
			// 没有显式目录条目的 JAR 目录对应的 ClassPathResource 无法解析为 URL，降级为 classpath 扫描。
			if (resource instanceof ClassPathResource classPathResource) {
				return loadFromClasspath(classPathResource.getPath());
			}
			throw ex;
		}

		String protocol = resourceUrl.getProtocol();

		if (!"jar".equals(protocol)) {
			throw new IOException("Unsupported resource protocol for JAR loading: " + protocol);
		}

		JarURLConnection jarConnection = (JarURLConnection) resourceUrl.openConnection();
		String entryPrefix = jarConnection.getEntryName();
		if (!entryPrefix.endsWith("/")) {
			entryPrefix = entryPrefix + "/";
		}
		return scanJarForSkills(jarConnection.getJarFile(), entryPrefix);
	}

	/**
	 * 使用 Spring 的 {@link ResourcePatternResolver} 在给定 classpath 前缀下发现 SKILL.md 文件。
	 * 对于缺少显式目录条目的 JAR，降级为手动 JAR 扫描（
	 * {@link PathMatchingResourcePatternResolver} 的已知限制 — 参见 Spring Framework issue #16711）。
	 * @param classpathPrefix 要扫描的 classpath 前缀（例如 "META-INF/resources/skills"）
	 * @return 从发现的 SKILL.md 文件解析出的 Skill 对象列表
	 * @throws IOException 如果扫描或读取时发生 I/O 错误
	 */
	private static List<Skill> loadFromClasspath(String classpathPrefix) throws IOException {
		// Primary: Spring's ResourcePatternResolver — works for well-formed JARs with
		// explicit directory entries and for resources on the filesystem.
		ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Resource[] resources = resolver.getResources("classpath*:" + classpathPrefix + "/**/SKILL.md");

		if (resources.length > 0) {
			List<Skill> skills = new ArrayList<>();
			for (Resource skillResource : resources) {
				try (InputStream is = skillResource.getInputStream()) {
					String basePath = deriveBasePathFromUrl(skillResource.getURL());
					skills.add(parseSkill(is, basePath));
				}
			}
			return skills;
		}

		// Fallback: Manual JAR scanning for JARs without directory entries.
		// Uses the same strategy as Spring's own
		// PathMatchingResourcePatternResolver.addAllClassLoaderJarRoots().
		return scanClasspathJarsForSkills(classpathPrefix);
	}

	/**
	 * 扫描所有 classpath JAR 中给定前缀下的 SKILL.md 文件。
	 * 通过 {@code ClassLoader.getResources("META-INF/MANIFEST.MF")} 发现 JAR —
	 * 这是 Spring 在标准 classpath 解析不足时内部使用的技术。
	 */
	private static List<Skill> scanClasspathJarsForSkills(String classpathPrefix) throws IOException {
		String prefix = classpathPrefix.endsWith("/") ? classpathPrefix : classpathPrefix + "/";

		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) {
			classLoader = Skills.class.getClassLoader();
		}

		List<Skill> skills = new ArrayList<>();

		Enumeration<URL> manifests = classLoader.getResources("META-INF/MANIFEST.MF");
		while (manifests.hasMoreElements()) {
			URL manifestUrl = manifests.nextElement();
			if (!"jar".equals(manifestUrl.getProtocol())) {
				continue;
			}

			JarURLConnection jarConnection = (JarURLConnection) manifestUrl.openConnection();
			skills.addAll(scanJarForSkills(jarConnection.getJarFile(), prefix));
		}

		return skills;
	}

	/**
	 * 扫描单个 JAR 文件中给定前缀下的 SKILL.md 条目。
	 * @param jarFile 要扫描的 JAR 文件
	 * @param entryPrefix 要匹配的条目前缀（必须以 '/' 结尾）
	 * @return 此 JAR 中找到的 Skill 对象列表
	 */
	private static List<Skill> scanJarForSkills(JarFile jarFile, String entryPrefix) throws IOException {
		List<Skill> skills = new ArrayList<>();
		Enumeration<JarEntry> entries = jarFile.entries();

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String entryName = entry.getName();

			if (!entry.isDirectory() && entryName.startsWith(entryPrefix)
					&& entryName.endsWith("/SKILL.md")) {
				try (InputStream is = jarFile.getInputStream(entry)) {
					skills.add(parseSkill(is, entryName));
				}
			}
		}
		return skills;
	}

	/**
	 * 从输入流中将 SKILL.md 文件解析为 {@link Skill}。
	 * @param is 包含 SKILL.md markdown 内容的输入流
	 * @param entryPath JAR 条目路径 — 用于推导基础目录
	 */
	private static Skill parseSkill(InputStream is, String entryPath) throws IOException {
		String markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
		MarkdownParser parser = new MarkdownParser(markdown);
		String basePath = entryPath.endsWith("/SKILL.md")
				? entryPath.substring(0, entryPath.lastIndexOf('/'))
				: entryPath;
		return new Skill(basePath, parser.getFrontMatter(), parser.getContent());
	}

	/**
	 * 从资源 URL 中推导 JAR 内部的基础路径，去除 SKILL.md 文件名和 {@code jar:file:...!/} 前缀。
	 */
	private static String deriveBasePathFromUrl(URL skillUrl) {
		String urlStr = skillUrl.toString();
		String basePath = urlStr.substring(0, urlStr.lastIndexOf("/SKILL.md"));
		if (basePath.contains("!/")) {
			basePath = basePath.substring(basePath.indexOf("!/") + 2);
		}
		return basePath;
	}

}
