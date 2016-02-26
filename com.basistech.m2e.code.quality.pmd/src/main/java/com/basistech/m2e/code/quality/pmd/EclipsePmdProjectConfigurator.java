/*******************************************************************************
 * Copyright 2010 Mohan KR
 * Copyright 2010 Basis Technology Corp.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.basistech.m2e.code.quality.pmd;

import static com.basistech.m2e.code.quality.pmd.PmdEclipseConstants.MAVEN_PLUGIN_ARTIFACTID;
import static com.basistech.m2e.code.quality.pmd.PmdEclipseConstants.MAVEN_PLUGIN_GROUPID;
import static com.basistech.m2e.code.quality.pmd.PmdEclipseConstants.PMD_RULESET_FILE;
import static com.basistech.m2e.code.quality.pmd.PmdEclipseConstants.PMD_SETTINGS_FILE;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
//import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.basistech.m2e.code.quality.shared.AbstractMavenPluginProjectConfigurator;
import com.basistech.m2e.code.quality.shared.MavenPluginWrapper;
import com.basistech.m2e.code.quality.shared.ResourceResolver;

import net.sourceforge.pmd.PMDException;
import net.sourceforge.pmd.RuleSet;
import net.sourceforge.pmd.RuleSetFactory;
import net.sourceforge.pmd.RuleSetNotFoundException;
import net.sourceforge.pmd.RuleSetReferenceId;
import net.sourceforge.pmd.eclipse.plugin.PMDPlugin;
import net.sourceforge.pmd.eclipse.runtime.builder.PMDNature;
import net.sourceforge.pmd.eclipse.runtime.properties.IProjectProperties;
import net.sourceforge.pmd.eclipse.runtime.properties.IProjectPropertiesManager;
import net.sourceforge.pmd.eclipse.runtime.properties.PropertiesException;
import net.sourceforge.pmd.eclipse.runtime.writer.WriterException;

public class EclipsePmdProjectConfigurator extends
        AbstractMavenPluginProjectConfigurator {

	// private static final String PMD_NATURE =
	// "net.sourceforge.pmd.eclipse.plugin.pmdNature";
	private static final String JAVA_NATURE = "org.eclipse.jdt.core.javanature";
	private static final Logger LOG =
	        LoggerFactory.getLogger(EclipsePmdProjectConfigurator.class);

	// create a rule set factory for instantiating rule sets
	private final RuleSetFactory factory = new RuleSetFactory();

	@Override
	protected String getMavenPluginGroupId() {
		return MAVEN_PLUGIN_GROUPID;
	}

	@Override
	protected String getMavenPluginArtifactId() {
		return MAVEN_PLUGIN_ARTIFACTID;
	}

	@Override
	protected String[] getMavenPluginGoal() {
		return new String[] {"check"};
	}

	@Override
	protected void handleProjectConfigurationChange(
	        final IMavenProjectFacade mavenProjectFacade,
	        final IProject project, final IProgressMonitor monitor,
	        final MavenPluginWrapper mavenPluginWrapper, MavenSession session)
	                throws CoreException {

		final MojoExecution execution = findMojoExecution(mavenPluginWrapper);
		MojoExecution pmdGoalExecution =
		        findForkedExecution(execution, "org.apache.maven.plugins",
		                "maven-pmd-plugin", "pmd");
		final MavenPluginConfigurationTranslator pluginCfgTranslator =
		        MavenPluginConfigurationTranslator.newInstance(this,
		                mavenProjectFacade.getMavenProject(monitor),
		                pmdGoalExecution, project, monitor);

		this.createOrUpdateEclipsePmdConfiguration(mavenPluginWrapper, project,
		        pluginCfgTranslator, monitor, session);

		addPMDNature(project, monitor);
	}

	// private static boolean addPMDNatureHere(final IProject project,
	// final IProgressMonitor monitor) throws CoreException {
	// boolean success = false;
	//
	// // unlike the one inside PMD, this carefully does NOT check for the
	// prerequisite
	// // Java nature, in case we end up in the wrong order.
	// if (project.hasNature(JAVA_NATURE) && !project.hasNature(PMD_NATURE)) {
	// final IProjectDescription description = project.getDescription();
	// final String[] natureIds = description.getNatureIds();
	// String[] newNatureIds = new String[natureIds.length + 1];
	// System.arraycopy(natureIds, 0, newNatureIds, 0, natureIds.length);
	// newNatureIds[natureIds.length] = PMD_NATURE;
	// description.setNatureIds(newNatureIds);
	// project.setDescription(description, monitor);
	// success = true;
	// }
	//
	// return success;
	// }

	private void addPMDNature(final IProject project,
	        final IProgressMonitor monitor) throws CoreException {
		if (project.hasNature(JAVA_NATURE)) {
			try {
				PMDNature.addPMDNature(project, monitor);
			} catch (CoreException pmdNatureProblem) {
				LOG.error("PMD plugin threw exception adding PMD nature",
				        pmdNatureProblem);
				throw pmdNatureProblem;
			}
		}
	}

	@Override
	protected void unconfigureEclipsePlugin(final IProject project,
	        final IProgressMonitor monitor) throws CoreException {
		IProjectPropertiesManager mgr =
		        PMDPlugin.getDefault().getPropertiesManager();
		try {
			IProjectProperties projectProperties =
			        mgr.loadProjectProperties(project);
			projectProperties.setPmdEnabled(false);
			projectProperties.setRuleSetStoredInProject(false);
			mgr.storeProjectProperties(projectProperties);
		} catch (PropertiesException ex) {
		}

		PMDNature.removePMDNature(project, monitor);
		// delete .pmdruleset if any
		final IResource pmdRulesetResource = project.getFile(PMD_RULESET_FILE);
		pmdRulesetResource.delete(IResource.FORCE, monitor);

		// delete .pmd if any
		final IResource pmdPropertiesResource =
		        project.getFile(PMD_SETTINGS_FILE);
		pmdPropertiesResource.delete(IResource.FORCE, monitor);
	}

	/**
	 * Configures the PMD plugin based on the POM contents
	 * 
	 * @throws CoreException
	 *             if the creation failed.
	 */
	private void createOrUpdateEclipsePmdConfiguration(
	        final MavenPluginWrapper pluginWrapper, final IProject project,
	        final MavenPluginConfigurationTranslator pluginCfgTranslator,
	        final IProgressMonitor monitor, MavenSession session)
	                throws CoreException {

		final MojoExecution execution = findMojoExecution(pluginWrapper);
		ResourceResolver resourceResolver =
		        this.getResourceResolver(execution, session,
		                project.getLocation());
		try {
			final RuleSet ruleset =
			        this.createPmdRuleSet(pluginCfgTranslator,
			                resourceResolver);

			this.buildAndAddPmdExcludeAndIncludePatternToRuleSet(
			        pluginCfgTranslator, ruleset);

			// persist the ruleset to a file under the project.
			final File rulesetFile =
			        writeRuleSet(project.getFile(PMD_RULESET_FILE), ruleset,
			                monitor);

			try {
				final IProjectPropertiesManager mgr =
				        PMDPlugin.getDefault().getPropertiesManager();
				final IProjectProperties projectProperties =
				        mgr.loadProjectProperties(project);
				projectProperties.setPmdEnabled(true);
				projectProperties.setRuleSetFile(rulesetFile.getAbsolutePath());
				projectProperties.setRuleSetStoredInProject(true);
				mgr.storeProjectProperties(projectProperties);
			} catch (PropertiesException ex) {
				// remove the files
				this.unconfigureEclipsePlugin(project, monitor);
			}
		} catch (PMDException ex) {
			// nothing to do, skip configuration
		}
	}

	private RuleSet createPmdRuleSet(
	        final MavenPluginConfigurationTranslator pluginCfgTranslator,
	        final ResourceResolver resourceResolver) throws CoreException,
	                PMDException {

		final RuleSet ruleSet = new RuleSet();
		ruleSet.setName("M2Eclipse PMD RuleSet");

		final List<String> rulesetStringLocations =
		        pluginCfgTranslator.getRulesets();

		for (final String loc : rulesetStringLocations) {
			RuleSetReferenceId ruleSetReferenceId = new RuleSetReferenceId(loc);
			final URL resolvedLocation =
			        resourceResolver.resolveLocation(ruleSetReferenceId
			                .getRuleSetFileName());

			if (resolvedLocation == null) {
				throw new PMDException(
				        String.format(
				                "Failed to resolve RuleSet from location [%s],SKIPPING Eclipse PMD configuration",
				                loc));
			}

			RuleSet ruleSetAtLocations;
			try {
				RuleSetReferenceId resolvedRuleSetReference =
				        new RuleSetReferenceId(loc) {

					        @Override
					        public InputStream getInputStream(ClassLoader arg0)
				                    throws RuleSetNotFoundException {
						        try {
							        return resolvedLocation.openStream();
						        } catch (IOException e) {
							        // ignore them.
						        }
						        LOG.warn("No ruleset found for {}", loc);
						        return null;
					        }
				        };
				ruleSetAtLocations =
				        this.factory.createRuleSet(resolvedRuleSetReference);
				ruleSet.addRuleSet(ruleSetAtLocations);
			} catch (RuleSetNotFoundException e) {
				LOG.error("Couldn't find ruleset {}", loc, e);
			}
		}

		return ruleSet;
	}

	/**
	 * Serializes the ruleset for configuring eclipse PMD plugin.
	 * 
	 * @param rulesetFile
	 *            the ruleset File resource.
	 * @param ruleSet
	 *            the {@code RuleSet} instance.
	 * @param monitor
	 *            the Progress monitor instance.
	 * @return the {@code File} instance of the ruleset file.
	 * @throws CoreException
	 */
	private File writeRuleSet(final IFile rulesetFile, final RuleSet ruleSet,
	        final IProgressMonitor monitor) throws CoreException {
		final PMDPlugin pmdPlugin = PMDPlugin.getDefault();

		ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
		try (BufferedOutputStream outputStream =
		        new BufferedOutputStream(new FileOutputStream(rulesetFile
		                .getLocation().toFile()));) {

			pmdPlugin.getRuleSetWriter().write(byteArrayStream, ruleSet);

			// ..and now we have two problems
			String fixedXml =
			        byteArrayStream.toString("UTF-8").replaceAll(
			                "\\<exclude\\>(.*)\\</exclude\\>",
			                "<exclude name=\"$1\"/>");

			outputStream.write(fixedXml.getBytes(Charset.forName("UTF-8")));

			rulesetFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
		} catch (IOException | WriterException ex) {
			//
		}
		return rulesetFile.getLocation().toFile();
	}

	private void buildAndAddPmdExcludeAndIncludePatternToRuleSet(
	        final MavenPluginConfigurationTranslator pluginCfgTranslator,
	        final RuleSet ruleset) {
		final List<String> excludeRoots = pluginCfgTranslator.getExcludeRoots();
		final List<String> includeRoots = pluginCfgTranslator.getIncludeRoots();
		final List<String> includePatterns = pluginCfgTranslator.getIncludes();

		// 1. check to see if any includes are specified. If they are then
		// to line up with the behavior of maven-pmd-plugin, excludes
		// don't make any sense at all or more specifically it is (ignored).
		final boolean includesSpecified = includePatterns.size() > 0;
		final Set<String> excludeRootsSet = new HashSet<>();
		excludeRootsSet.addAll(excludeRoots);

		if (includesSpecified) {
			// Add all includeRoots to excludeRoots.
			// Add all includeRoots too..
			excludeRootsSet.addAll(includeRoots);
		} else {
			final List<String> excludePatterns =
			        pluginCfgTranslator.getExcludes();
			// 2.) As per spec. add excludes pattern to all *includeRoots*.
			for (String ir : includeRoots) {
				for (String ep : excludePatterns) {
					String fullPattern = ".*" + ir + ep;
					ruleset.addExcludePattern(StringUtils.replace(fullPattern,
					        ".*.*", ".*"));
				}
			}
		}
		// 1.) Do the excludeRoots first
		for (String er : excludeRootsSet) {
			ruleset.addExcludePattern(".*" + er);
		}
		// 3.) Now all includes
		for (String ir : includeRoots) {
			for (String ip : includePatterns) {
				String fullPattern = ".*" + ir + ip;
				ruleset.addIncludePattern(StringUtils.replace(fullPattern,
				        ".*.*", ".*"));
			}
		}
	}

	private MojoExecution findMojoExecution(
	        final MavenPluginWrapper mavenPluginWrapper) throws CoreException {
		final List<MojoExecution> mojoExecutions =
		        mavenPluginWrapper.getMojoExecutions();
		if (mojoExecutions.size() != 1) {
			throw new CoreException(new Status(IStatus.ERROR,
			        PmdEclipseConstants.PLUGIN_ID,
			        "Wrong number of executions. Expected 1. Found "
			                + mojoExecutions.size()));
		}
		final MojoExecution execution = mojoExecutions.get(0);
		return execution;
	}

}
