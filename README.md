# Mockito Maven Plugin

This plugins allows you to easily configure Mockito in Maven projects.

## The problem

Mockito works by adding a Java agent to the JRE during runtime.
Java 21 warns about this - future versions will completely disable this functionality for security
reasons.
See [JEP 451](https://openjdk.org/jeps/451) for more information.

If you run your maven build with Java 21 or later, you will see this warning in your logs:

> Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of
> the JDK. Please add Mockito as an agent to your build as described in Mockito's
> documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#0.3  
> OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has
> been appended  
> WARNING: A Java agent has been loaded dynamically (
> /home/pjeschke/.m2/repository/net/bytebuddy/byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar)  
> WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning  
> WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information  
> WARNING: Dynamic loading of agents will be disallowed by default in a future release

## The solution (without this plugin)

The suggested way of fixing this is to explicitly add the agent to the execution of your test plugin:

```xml

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>properties</goal>
            </goals>
        </execution>
    </executions>
</plugin>
<plugin>
<groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-surefire-plugin</artifactId>
<configuration>
    <argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
</configuration>
</plugin>
```

But this can easily clash if you have other plugins interacting with the surefire argLine.

## The solution (with this plugin)

Add this to your plugins:

```xml

<plugin>
    <groupId>dev.jeschke.maven.mockito</groupId>
    <artifactId>mockito-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>prepareAgent</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The plugin will update the arguments that the test plugin will use to start the JVM.
Supported test plugins are Maven Surefire and Tycho Surefire.

You can check out this projects pom for an example.

## Configuration

| Property        | Description                                                                                                                                                                                      | Type    | Default                                                                                                  |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|----------------------------------------------------------------------------------------------------------|
| propertyName    | The maven property that will be adjusted to include the configuration. You shouldn't have to update this unless you use a test plugin besides `maven-surefire-plugin` or `tycho-surefire-plugin` | String  | Will be auto selected based on the presence of either `maven-surefire-plugin` or `tycho-surefire-plugin` |
| agentGroupId    | The groupId of the dependency that contains the agent.                                                                                                                                           | String  | org.mockito                                                                                              |
| agentArtifactId | the artifactId of the dependency that contains the agent.                                                                                                                                        | String  | mockito-core                                                                                             |
| skipPrepare     | Skip preparing the agent. If explicitly set to false, the agent will be prepared even if tests are skipped.                                                                                      | boolean | null                                                                                                     |
| failSilent      | If set to true, the plugin will not cause a build failure if the agent can't be found.                                                                                                           | boolean | false                                                                                                    |

Additionally, the plugin also honors the property "skipTests".
If tests are skipped, there's no need to attach the Mockito agent.
Should you want to prepare the agent anyway, you can set skipPrepare to `false` which has priority over skipTests.