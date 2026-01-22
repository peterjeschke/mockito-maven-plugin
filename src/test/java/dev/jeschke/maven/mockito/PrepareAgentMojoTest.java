package dev.jeschke.maven.mockito;

import static java.nio.file.Files.createTempFile;
import static java.util.Collections.*;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrepareAgentMojoTest {
    @TempDir
    private Path tmpDir;

    @Mock
    private MavenProject project;

    private Properties properties;
    private PrepareAgentMojo mojo;

    @BeforeEach
    public void setup() {
        mojo = new PrepareAgentMojo(project);
        properties = new Properties();
        lenient().when(project.getProperties()).thenReturn(properties);
        lenient().when(project.getBuildPlugins()).thenReturn(emptyList());
    }

    @Test
    void execute_defaultValues() throws MojoFailureException, IOException {
        final Path artifactFile = createTempFile(tmpDir, null, ".jar");
        when(project.getArtifacts()).thenReturn(singleton(buildArtifact("org.mockito", "mockito-core", artifactFile)));

        mojo.execute();

        assertThat(properties)
                .extractingByKey("argLine", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + artifactFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_customValues() throws MojoFailureException, IOException {
        final Path artifactFile = createTempFile(tmpDir, null, ".jar");
        when(project.getArtifacts()).thenReturn(singleton(buildArtifact("com.example", "example", artifactFile)));
        mojo.setPropertyName("customArg");
        mojo.setAgentGroupId("com.example");
        mojo.setAgentArtifactId("example");

        mojo.execute();

        assertThat(properties)
                .doesNotContainKey("argLine")
                .extractingByKey("customArg", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + artifactFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_autoDetectTycho() throws MojoFailureException, IOException {
        final Path artifactFile = createTempFile(tmpDir, null, ".jar");
        when(project.getArtifacts()).thenReturn(singleton(buildArtifact("org.mockito", "mockito-core", artifactFile)));
        when(project.getBuildPlugins())
                .thenReturn(singletonList(buildPlugin("org.eclipse.tycho", "tycho-surefire-plugin")));

        mojo.execute();

        assertThat(properties)
                .doesNotContainKey("argLine")
                .extractingByKey("tycho.testArgLine", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + artifactFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_manualPropertyNameOverridesTycho() throws MojoFailureException, IOException {
        final Path artifactFile = createTempFile(tmpDir, null, ".jar");
        when(project.getArtifacts()).thenReturn(singleton(buildArtifact("org.mockito", "mockito-core", artifactFile)));
        lenient()
                .when(project.getBuildPlugins())
                .thenReturn(singletonList(buildPlugin("org.eclipse.tycho", "tycho-surefire-plugin")));
        mojo.setPropertyName("customArg");

        mojo.execute();

        assertThat(properties)
                .doesNotContainKey("argLine")
                .extractingByKey("customArg", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + artifactFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_failsWithoutArtifacts() {
        when(project.getArtifacts()).thenReturn(emptySet());

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void execute_failsSilently() {
        when(project.getArtifacts()).thenReturn(emptySet());
        mojo.setFailSilent(true);

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void execute_nullVerfication_project() {
        assertThatThrownBy(() -> new PrepareAgentMojo(null)).isNotNull();
    }

    @Test
    void execute_nullVerfication_agentGroupId() {
        mojo.setAgentGroupId(null);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void execute_nullVerfication_agentArtifactId() {
        mojo.setAgentArtifactId(null);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @MethodSource("skipFlags")
    @ParameterizedTest(name = "skipPrepare={0} skipTests={1} shouldSkip={2}")
    void execute_skipFlags(final Boolean skipPrepare, final boolean skipTests, final boolean shouldSkip)
            throws IOException, MojoFailureException {
        mojo.setSkipPrepare(skipPrepare);
        mojo.setSkipTests(skipTests);

        final Path artifactFile = createTempFile(tmpDir, null, ".jar");
        lenient()
                .when(project.getArtifacts())
                .thenReturn(singleton(buildArtifact("org.mockito", "mockito-core", artifactFile)));

        mojo.execute();

        if (shouldSkip) {
            assertThat(properties).doesNotContainKey("argLine");
        } else {
            assertThat(properties).containsKey("argLine");
        }
    }

    @Test
    void execute_versionLessThanThreshold_usesByteBuddyAgent() throws MojoFailureException, IOException {
        final Path mockitoFile = createTempFile(tmpDir, null, ".jar");
        final Path byteBuddyFile = createTempFile(tmpDir, null, ".jar");

        final Artifact mockito = buildArtifact("org.mockito", "mockito-core", mockitoFile, "5.13.0");
        final Artifact byteBuddy = buildArtifact("net.bytebuddy", "byte-buddy-agent", byteBuddyFile);
        when(project.getArtifacts()).thenReturn(new HashSet<>(java.util.Arrays.asList(mockito, byteBuddy)));

        mojo.execute();

        assertThat(properties)
                .extractingByKey("argLine", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + byteBuddyFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_versionGreaterOrEqualThreshold_usesMockitoAgent() throws MojoFailureException, IOException {
        final Path mockitoFile = createTempFile(tmpDir, null, ".jar");
        final Artifact mockito = buildArtifact("org.mockito", "mockito-core", mockitoFile, "5.15.0");
        when(project.getArtifacts()).thenReturn(singleton(mockito));

        mojo.execute();

        assertThat(properties)
                .extractingByKey("argLine", STRING)
                .isEqualToIgnoringWhitespace("-javaagent:\"" + mockitoFile.toAbsolutePath() + "\"");
    }

    @Test
    void execute_versionCheckFailsWhenMockitoMissingAndNonSilent() throws IOException {
        final Path agentFile = createTempFile(tmpDir, null, ".jar");
        final Artifact agent = buildArtifact("net.bytebuddy", "byte-buddy-agent", agentFile);
        when(project.getArtifacts()).thenReturn(singleton(agent));
        mojo.setFailSilent(false);

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    static Stream<Arguments> skipFlags() {
        return Stream.of(
                arguments(false, false, false),
                arguments(false, true, false),
                arguments(true, false, true),
                arguments(true, true, true),
                arguments(null, false, false),
                arguments(null, true, true));
    }

    private Artifact buildArtifact(final String groupId, final String artifactId, final Path artifactFile) {
        return buildArtifact(groupId, artifactId, artifactFile, "5.15.0");
    }

    private Artifact buildArtifact(
            final String groupId, final String artifactId, final Path artifactFile, final String version) {
        final DefaultArtifact artifact =
                new DefaultArtifact(groupId, artifactId, version, "test", "jar", "jar", new DefaultArtifactHandler());
        artifact.setFile(artifactFile.toFile());
        return artifact;
    }

    private Plugin buildPlugin(final String groupId, final String artifactId) {
        final Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        return plugin;
    }
}
