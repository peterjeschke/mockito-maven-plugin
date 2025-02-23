package dev.jeschke.maven.mockito;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;
import static org.apache.maven.plugins.annotations.LifecyclePhase.INITIALIZE;
import static org.apache.maven.plugins.annotations.ResolutionScope.TEST;

import java.io.File;
import java.util.Optional;
import java.util.Properties;
import javax.inject.Inject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Prepares the Mockito agent by adding it to the command line of test plugins.
 * <p>
 * If the defaults are not overridden, the dependency "org.mockito:mockito-core" has to be in the list of dependencies.
 * If the dependency is not available, the build will fail. This behavior can be changed by setting failSilent.
 * <p>
 * Supports the following test plugins:
 * <ul>
 * <li> maven-surefire-plugin (default)
 * <li> tycho-surefire-plugin
 * </ul>
 */
@Execute(phase = INITIALIZE, goal = "prepareAgent")
@Mojo(name = "prepareAgent", requiresDependencyResolution = TEST, defaultPhase = INITIALIZE, threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    private static final String TYCHO_SUREFIRE_GROUP_ID = "org.eclipse.tycho";
    private static final String TYCHO_SUREFIRE_ARTIFACT_ID = "tycho-surefire-plugin";
    private static final String TYCHO_ARGLINE_PROPERTY = "tycho.testArgLine";
    private static final String SUREFIRE_ARGLINE_PROPERTY = "argLine";

    private final MavenProject project;
    private String propertyName = null;
    private String agentGroupId = "org.mockito";
    private String agentArtifactId = "mockito-core";
    private Boolean skipPrepare = null;
    private boolean skipTests = false;
    private boolean failSilent = false;

    @Inject
    public PrepareAgentMojo(final MavenProject project) {
        this.project = requireNonNull(project);
    }

    @Override
    public void execute() throws MojoFailureException {
        if (skipTests && skipPrepare != FALSE) {
            getLog().info("Tests are skipped");
            return;
        }
        if (skipPrepare == TRUE) {
            getLog().info("Goal is skipped");
            return;
        }
        if (isNullOrEmpty(agentGroupId) || isNullOrEmpty(agentArtifactId)) {
            throw new MojoFailureException("Both agentGroupId and agentArtifactId must be set");
        }
        final String actualPropertyName = getPropertyName();
        final Properties properties = project.getProperties();
        final String existingArgLine = properties.getProperty(actualPropertyName, "");
        final Optional<String> artifactPath = buildArtifactPath();
        if (!artifactPath.isPresent()) {
            final String artifact = String.format("%s:%s", agentGroupId, agentArtifactId);
            if (failSilent) {
                getLog().info(String.format("Could not resolve artifact %s, skipping step", artifact));
                return;
            }
            throw new MojoFailureException(String.format("Could not resolve artifact %s", artifact));
        }
        final String mockitoArguments = "-javaagent:" + artifactPath.get();
        final String newArgline = mockitoArguments + " " + existingArgLine;
        properties.setProperty(actualPropertyName, newArgline);
        getLog().info(String.format("%s set to %s", actualPropertyName, newArgline));
    }

    private String getPropertyName() {
        if (propertyName != null) {
            return propertyName;
        }
        if (project.getBuildPlugins().stream().anyMatch(this::isTychoTestPlugin)) {
            return TYCHO_ARGLINE_PROPERTY;
        }
        return SUREFIRE_ARGLINE_PROPERTY;
    }

    private Optional<String> buildArtifactPath() {
        return project.getArtifacts().stream()
                .filter(this::isAgentArtifact)
                .findFirst()
                .map(Artifact::getFile)
                .map(File::getAbsolutePath);
    }

    private boolean isTychoTestPlugin(final Plugin plugin) {
        return TYCHO_SUREFIRE_GROUP_ID.equals(plugin.getGroupId())
                && TYCHO_SUREFIRE_ARTIFACT_ID.equals(plugin.getArtifactId());
    }

    private boolean isAgentArtifact(final Artifact artifact) {
        return agentGroupId.equals(artifact.getGroupId()) && agentArtifactId.equals(artifact.getArtifactId());
    }

    private boolean isNullOrEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    /**
     * The maven property to which the command line arguments should be added. If not set, the value will be determined
     * based on the presence of a test plugin.
     * <p>
     * Supported test plugins: maven-surefire-plugin (default), tycho-surefire-plugin
     */
    @Parameter(name = "propertyName", property = "mockito.agentPropertyName")
    public void setPropertyName(final String propertyName) {
        this.propertyName = propertyName;
    }

    /**
     * The groupId of the dependency that contains the agent.
     */
    @Parameter(name = "agentGroupId", property = "mockito.agentGroupId", defaultValue = "org.mockito")
    public void setAgentGroupId(final String agentGroupId) {
        this.agentGroupId = agentGroupId;
    }

    /**
     * The artifactId of the dependency that contains the agent.
     */
    @Parameter(name = "agentArtifactId", property = "mockito.agentArtifactId", defaultValue = "mockito-core")
    public void setAgentArtifactId(final String agentArtifactId) {
        this.agentArtifactId = agentArtifactId;
    }

    /**
     * Whether to skip this goal. If set to false, the goal will run even if tests are disabled.
     */
    @Parameter(name = "skipPrepare", property = "mockito.skipPrepare", defaultValue = "false")
    public void setSkipPrepare(final Boolean skipPrepare) {
        this.skipPrepare = skipPrepare;
    }

    /**
     * Whether to skip this goal. If skipPrepare is set to false, this parameter has no influence.
     */
    @Parameter(property = "skipTests", defaultValue = "false")
    public void setSkipTests(final boolean skipTests) {
        this.skipTests = skipTests;
    }

    /**
     * If enabled, the goal will not fail the build if the agent artifact is not present.
     */
    @Parameter(name = "failSilent", property = "mockito.failSilent", defaultValue = "false")
    public void setFailSilent(final boolean failSilent) {
        this.failSilent = failSilent;
    }
}
