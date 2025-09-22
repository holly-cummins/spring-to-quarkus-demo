This demo shows how to migrate a Spring Boot application to Quarkus, and how to measure the performance gains along the way. It can all be live-coded, showing how easy migrating can be.

## Preparation

The supporting scripts for this demo assume MacOS. You will need the `http` utility:

```shell
brew install httpie
```

You will also need a container runtime, such as Podman or Docker.

Finally, you will need `jq`. 

## The Spring Boot Application 


Start with `quarkus-spring-todo-app`. This is the [Quarkus todo sample](https://github.com/cescoffier/quarkus-todo-app), rewritten to Spring Boot 3.3.0. There is a single test implemented using Test Containers and Rest Assured (using Rest Assured isn't strictly idiomatic Spring, but it makes things easier later on.) For examples of migrating tests, see [Eric Deandrea](https://github.com/edeandrea)'s comprehensive "Spring to Quarkus" [code samples](https://github.com/quarkus-for-spring-developers/examples). 

### JVM mode

Package it and launch it:

```
cd spring-todo-app
./mvnw package
```

Before going further, start the database that the live instance will be using. Once that's done, start the app.

```
just start-infra
java -jar target/spring-todo-app-0.0.1-SNAPSHOT.jar -Xmx256m -Xms256m
```
Setting the fixed `-Xmx` and `-Xms` is important to reduce warmup effects and give apples-to-apples comparisons.

Now it's time for a mini-stress test, by running a [defined set of actions](https://github.com/cescoffier/spring-to-quarkus-demo/blob/main/justfile#L15). Before every run, make sure you have an empty database, by running `just restart-infra`. In a new window, at the top-level of the repository, run

```
just stress
```

Collect the startup time and footprint after the stress actions complete. 
You can read the startup time from the application log. It will be something like 

```
2024-06-27T11:56:59.475+01:00  INFO 37043 --- [           main] m.escoffier.spring.todo.TodoApplication  : Started TodoApplication in 2.779 seconds (process running for 3.247)
```

When measuring the footprint of a Java application, you should measure Resident Set Size (RSS) and not the JVM heap size which is only a small part of the overall memory consumption. The JVM not only allocates native memory for heap (-Xms, -Xmx) but also structures required by the jvm to run your application, such as compiled code and class metadata.

On MacOS, to measure the RSS, run

```shell
ps aux | grep -i java | grep -i todo | grep -v grep | awk {'print $2'} | xargs ps x -o pid,rss,command -p | awk '{$2=int($2/1024)"M";}{ print;}
```
This finds the process ID, makes sure to exclude the grep process itself, and then runs `ps` on that process to get the RSS, and then converts KB to MB. 
You should get something like 

```shell
  PID    RSS COMMAND
37043 267M java -jar target/spring-todo-app-0.0.1-SNAPSHOT.jar -Xmx256m -Xms256m
```

This shows an RSS of 254,672 KB.

Instructions on measuring RSS on other platforms are available [here](https://quarkus.io/guides/performance-measure).


### Native mode

Repeat the measurement for native. Be aware, this may take some time, and there are about a hundred expected warnings. 

Don't forget to clear the database before the test with `just restart-infra`.

```shell
./mvnw clean native:compile -Pnative
```

> [!NOTE]
> You need to use the `native:compile` goal instead of `package` because Spring Boot delegates native compilation to the graal maven plugin rather than wrapping it or capturing it via the `package` goal.
>
> See https://docs.spring.io/spring-boot/how-to/native-image/developing-your-first-application.html#howto.native-image.developing-your-first-application.native-build-tools.maven for details.

## Quarkus application

Now we get to Quarkify the application. Quarkus's Spring compatibility libraries make the switch to Quarkus straightforward. 

The simplest way to convert the pom.xml is to make a new one with `quarkus` cli, and overwrite the old one.

```shell
quarkus create
cd code-with-quarkus 
quarkus ext add spring-web spring-data-jpa jdbc-postgresql rest-jackson hibernate-validator
cp pom.xml ../
cd ..
```

You can also edit the pom by hand, which is more compelling as a demo, but harder, because it's a lot of xml to wrangle. 

Whichever way you do the transformation, once the pom is correct, you remove code which is no longer needed. Delete [`TodoApplication.java`](spring-todo-app/src/main/java/me/escoffier/spring/todo/TodoApplication.java), [`ContainersConfig.java`](spring-todo-app/src/test/java/me/escoffier/spring/todo/ContainersConfig.java), and [`TestApplication.java`](spring-todo-app/src/test/java/me/escoffier/spring/todo/TestApplication.java).

Then remove all of the setup code from [`TodoAppApplicationTests`](spring-todo-app/src/test/java/me/escoffier/spring/todo/TodoAppApplicationTests.java). The following code should all be removed:

```java
@Autowired
private WebApplicationContext context;

@LocalServerPort
int randomServerPort;

@BeforeEach
public void setup() {
	RestAssuredMockMvc.webAppContextSetup(context);
	RestAssured.port = randomServerPort;
	RestAssured.requestSpecification = new RequestSpecBuilder()
			.setContentType(ContentType.JSON)
			.setAccept(ContentType.JSON)
			.build();
}
```

Also, remove all of the annotations on the `TodoAppApplicationTests` class:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainersConfig.class)
```

and replaced them with

```java
@QuarkusTest
```

It's an impressive amount of code which just goes away with the switch to the Quarkus libraries. 
Doing the migration should take at most 5min. If you get lost, you can compare to the app in `quarkus-spring-todo-app.`

### JVM mode

The application still uses an "old-school" entity and a JPA repository. Make sure the test passes, using `mvn clean verify` (don't forget the `clean`, or you'll get failures from the deleted application classes in `target`). 

Before running the production app, one more change is needed, to migrate the config. 
Rename `src/main/resources/application.yaml` to `application.properties`.
Edit it by hand, to end up with 

```properties
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/quarkus_test
%prod.quarkus.datasource.username=quarkus_test
%prod.quarkus.datasource.password=quarkus_test
%prod.quarkus.hibernate-orm.database.generation=update
```

Then, repeat the measurements: new DB, same -Xmx-Xms, same operations, highlight the startup time and RSS.

```
just restart-infra
java -jar ./target/quarkus-app/quarkus-run.jar  -Xmx256m -Xms256m
```

### Native 

Do the same for native:

```
./mvnw clean package -Pnative
target/code-with-quarkus-1.0.0-SNAPSHOT-runner -Xmx256m -Xms256m
```

The important thing to note is that it's the same app, same code, with the same features – just smaller and faster.

## Cleaned up Quarkus application

You can go further and perform a second Quarkification. 
- Switch the database access to [active record](https://www.martinfowler.com/eaaCatalog/activeRecord.html). To do this, delete `TodoRepository`, update `Todo` to extend `PanacheEntity`. You can also delete all of the getters and setters in `Todo`. 
- Remove all the `@ResponseBody` annotations in the controllers, since they are no longer needed

Measure the throughout and RSS again. The difference should be negligible, with the benefit of a smaller, cleaner, codebase.

## Demo notes

Make sure you always reproduce things the right way. You restart my DB so you have something comparable, use the same GraalVM version, same heap ...  

If you have a friend and a whiteboard, ask them to write down the performance numbers while doing the demo. This is useful for comparisons later on.
