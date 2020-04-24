FROM openjdk:13-alpine

WORKDIR /

COPY target/test-splitting-orb-agent-0.1.0-standalone.jar test-splitting-orb-agent-0.1.0-standalone.jar
EXPOSE 6789

CMD java -jar test-splitting-orb-agent-0.1.0-standalone.jar
