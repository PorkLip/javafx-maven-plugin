/*
 * Copyright (c) 2021, Gluon
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openjfx;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.shared.invoker.*;
import org.openjfx.model.JavaFXDependency;
import org.openjfx.model.JavaFXModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A temporary mojo introduced to run JavaFX applications
 * with Java 17 Maven artifacts.
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.PROCESS_RESOURCES)
public class JavaFXRunFixMojo extends JavaFXBaseMojo {

    private static final String ARCHITECTURE_AMD64 = "amd64";

    @Parameter(readonly = true, required = true, defaultValue = "${basedir}/pom.xml")
    String pom;

    // gluonfx-maven-plugin creates `runPom.xml` for gluonfx:runagent goal
    @Parameter(readonly = true, required = true, defaultValue = "${basedir}/runPom.xml")
    String runpom;

    @Parameter(readonly = true, required = true, defaultValue = "${project.basedir}/modifiedPom.xml")
    String modifiedPom;

    @Parameter(defaultValue = "${session}", readonly = true)
    MavenSession session;

    @Parameter(defaultValue = "${javafx.platform}", readonly = true)
    String javafxPlatform;

    @Override
    public void execute() throws MojoExecutionException {
        String PLATFORM = javafxPlatform != null ? javafxPlatform : this.getClassifier();

        final InvocationRequest invocationRequest = new DefaultInvocationRequest();
        invocationRequest.setProfiles(project.getActiveProfiles().stream()
                .map(Profile::getId)
                .collect(Collectors.toList()));
        invocationRequest.setProperties(session.getRequest().getUserProperties());

        // 1. Create modified pom
        File modifiedPomFile = new File(modifiedPom);
        try (InputStream is = new FileInputStream(new File(runpom).exists() ? runpom : pom)) {
            // 2. Create model from current pom
            Model model = new MavenXpp3Reader().read(is);

            Set<JavaFXDependency> javaFXDependencies = new HashSet<>();
            List<Dependency> toRemove = new ArrayList<>();
            // 3. Check for dependencies
            for (Dependency p : model.getDependencies()) {
                if (p.getGroupId().equalsIgnoreCase("org.openjfx")) {
                    toRemove.add(p);
                    final Optional<JavaFXModule> javaFXModule = JavaFXModule.fromArtifactName(p.getArtifactId());
                    javaFXModule.ifPresent(module -> {
                        javaFXDependencies.add(module.getMavenDependency(PLATFORM, p.getVersion()));
                        javaFXDependencies.addAll(module.getTransitiveMavenDependencies(PLATFORM, p.getVersion()));
                    });
                }
            }
            model.getDependencies().removeAll(toRemove);
            model.getDependencies().addAll(javaFXDependencies);

            // 4. Serialize new pom
            try (OutputStream os = new FileOutputStream(modifiedPomFile)) {
                new MavenXpp3Writer().write(os, model);
            }
        } catch (Exception e) {
            if (modifiedPomFile.exists()) {
                modifiedPomFile.delete();
            }
            throw new MojoExecutionException("Error generating modified pom", e);
        }

        invocationRequest.setPomFile(modifiedPomFile);
        invocationRequest.setGoals(Collections.singletonList("javafx:dorun"));
        invocationRequest.setUserSettingsFile(session.getRequest().getUserSettingsFile());

        final Invoker invoker = new DefaultInvoker();
        try {
            final InvocationResult invocationResult = invoker.execute(invocationRequest);
            if (invocationResult.getExitCode() != 0) {
                throw new MojoExecutionException("Error, javafx:run failed", invocationResult.getExecutionException());
            }
        } catch (MavenInvocationException e) {
            e.printStackTrace();
            throw new MojoExecutionException("Error", e);
        } finally {
            if (modifiedPomFile.exists()) {
                modifiedPomFile.delete();
            }
        }
    }

    /**
     * Get the classifier from OS and Architecture
     *
     * @return A string
     * @throws MojoExecutionException if the OS isn't supported
     */
    private String getClassifier() throws MojoExecutionException {
        if (SystemUtils.IS_OS_MAC) {
            return "mac" + this.getArchitecture();
        } else if (SystemUtils.IS_OS_LINUX) {
            return "linux" + this.getArchitecture();
        } else if (SystemUtils.IS_OS_WINDOWS) {
            return "win" + this.getArchitecture();
        } else {
            throw new MojoExecutionException("Error, " + SystemUtils.OS_NAME + " not supported");
        }
    }

    /**
     * Get the architecture part of the classifier, {@link #ARCHITECTURE_AMD64} is the default build
     *
     * @return A string
     */
    private String getArchitecture() {
        if (StringUtils.isBlank(SystemUtils.OS_ARCH) || ARCHITECTURE_AMD64.equals(SystemUtils.OS_ARCH)) {
            return "";
        }
        return "-" + SystemUtils.OS_ARCH;
    }
}

