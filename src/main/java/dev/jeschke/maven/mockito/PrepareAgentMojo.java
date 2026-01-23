package dev.jeschke.maven.mockito;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;
import static org.apache.maven.plugins.annotations.LifecyclePhase.INITIALIZE;
import static org.apache.maven.plugins.annotations.ResolutionScope.TEST;

import java.util.Optional;
import java.util.Properties;
import javax.inject.Inject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ComparableVersion;
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

    private static final String DEFAULT_GROUP_ID = "org.mockito";
    private static final String DEFAULT_ARTIFACT_ID = "mockito-core";
    private static final String BYTE_BUDDY_GROUP_ID = "net.bytebuddy";
    private static final String BYTE_BUDDY_ARTIFACT_ID = "byte-buddy-agent";
    private static final String TYCHO_SUREFIRE_GROUP_ID = "org.eclipse.tycho";
    private static final String TYCHO_SUREFIRE_ARTIFACT_ID = "tycho-surefire-plugin";
    private static final String TYCHO_ARGLINE_PROPERTY = "tycho.testArgLine";
    private static final String SUREFIRE_ARGLINE_PROPERTY = "argLine";
    // 5.14.0 is the version that introduced the agent in mockito-core. Older versions need to fall back to
    // byte-buddy-agent
    private static final ComparableVersion MOCKITO_VERSION_THRESHOLD = new ComparableVersion("5.14.0");

    private final MavenProject project;
    private String propertyName = null;
    private String agentGroupId = DEFAULT_GROUP_ID;
    private String agentArtifactId = DEFAULT_ARTIFACT_ID;
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
            final String message = "Either the agentGroupId or agentArtifactId are not set. Both must be set for the"
                    + " plugin to work. Please check your configuration.";
            if (failSilent) {
                getLog().warn(message);
                return;
            }
            throw new MojoFailureException(message);
        }
        final String actualPropertyName = getPropertyName();
        final Properties properties = project.getProperties();
        final String existingArgLine = properties.getProperty(actualPropertyName, "");
        final Optional<Artifact> artifact = getArtifact(agentGroupId, agentArtifactId);
        if (!artifact.isPresent()) {
            final String message = String.format(
                    "Could not resolve artifact %s:%s, skipping step. Make sure that your"
                            + " project depends on the configured artifact or update your configuration to match the"
                            + " expected artifact.",
                    agentGroupId, agentArtifactId);
            if (failSilent) {
                getLog().info(message);
                return;
            }
            throw new MojoFailureException(message);
        }
        final Artifact actualArtifact;
        try {
            actualArtifact = ensureMockitoVersionCompatibility(artifact.get());
        } catch (MojoFailureException e) {
            if (failSilent) {
                getLog().error(e.getMessage());
                return;
            }
            throw e;
        }
        final String artifactPath = buildArtifactPath(actualArtifact);
        final String mockitoArguments = "-javaagent:\"" + artifactPath + "\"";
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

    private String buildArtifactPath(Artifact artifact) {
        return artifact.getFile().getAbsolutePath();
    }

    private Optional<Artifact> getArtifact(final String groupId, final String artifactId) {
        return project.getArtifacts().stream()
                .filter(artifact ->
                        groupId.equals(artifact.getGroupId()) && artifactId.equals(artifact.getArtifactId()))
                .findFirst();
    }

    private boolean isTychoTestPlugin(final Plugin plugin) {
        return TYCHO_SUREFIRE_GROUP_ID.equals(plugin.getGroupId())
                && TYCHO_SUREFIRE_ARTIFACT_ID.equals(plugin.getArtifactId());
    }

    private boolean isNullOrEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    /**
     * If the installed mockito-version is too old, fall back to byte-buddy instead.
     */
    private Artifact ensureMockitoVersionCompatibility(final Artifact agentArtifact) throws MojoFailureException {
        if (!DEFAULT_GROUP_ID.equals(agentArtifact.getGroupId())
                || !DEFAULT_ARTIFACT_ID.equals(agentArtifact.getArtifactId())) {
            return agentArtifact;
        }
        final String version = agentArtifact.getVersion();

        if (new ComparableVersion(version).compareTo(MOCKITO_VERSION_THRESHOLD) < 0) {
            final Optional<Artifact> byteBuddyArtifact = getArtifact(BYTE_BUDDY_GROUP_ID, BYTE_BUDDY_ARTIFACT_ID);
            if (!byteBuddyArtifact.isPresent()) {
                throw new MojoFailureException("Found a mockito-core version that should depend on byte-buddy but "
                        + "couldn't find byte-buddy. This might be caused by a dependency misconfiguration. Check if you "
                        + "have mistakenly excluded byte-buddy from your build.");
            }

            return byteBuddyArtifact.get();
        }
        return agentArtifact;
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
    @Parameter(name = "agentGroupId", property = "mockito.agentGroupId", defaultValue = DEFAULT_GROUP_ID)
    public void setAgentGroupId(final String agentGroupId) {
        this.agentGroupId = agentGroupId;
    }

    /**
     * The artifactId of the dependency that contains the agent.
     */
    @Parameter(name = "agentArtifactId", property = "mockito.agentArtifactId", defaultValue = DEFAULT_ARTIFACT_ID)
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
