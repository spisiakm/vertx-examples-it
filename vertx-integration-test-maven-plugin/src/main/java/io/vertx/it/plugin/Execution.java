package io.vertx.it.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.google.common.collect.ImmutableMap;
import com.jayway.awaitility.Awaitility;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.scriptinterpreter.FileLogger;
import org.apache.maven.shared.scriptinterpreter.RunFailureException;
import org.apache.maven.shared.scriptinterpreter.ScriptRunner;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class Execution {

  private Status status = Status.SKIPPED;
  private Throwable exception;
  private long executionTime;
  private Executor main;
  private Executor client;
  private String command;
  private String clientCommand;

  public void setExecutionTime(long executionTime) {
    this.executionTime = executionTime;
  }

  public void dumpReport(File reportDirectory) throws IOException {
    Reporter.createReportForExecution(this, reportDirectory);
  }

  public String getFullName() {
    return run.name() + "#" + name;
  }

  public Throwable getReason() {
    return exception;
  }

  enum Status {
    SUCCESS,
    ERROR,
    FAILURE,
    SKIPPED
  }

  private final Log logger;
  private final JsonNode node;
  private final Run run;
  private final String name;

  /**
   * The scripter runner that is responsible to execute hook scripts.
   */
  private ScriptRunner scriptRunner;
  private String postCheck;


  public Execution(Run run, String name, JsonNode execution, Log logger) {
    this.logger = logger;
    this.name = name;
    this.node = execution;
    this.run = run;
    initScriptRunner();
  }

  private void initScriptRunner() {
    scriptRunner = new ScriptRunner(logger);
    GroovyScriptHelper.addClasspathToScript(scriptRunner);
    scriptRunner.setScriptEncoding("utf-8");
  }

  public void execute() throws IOException, RunFailureException {
    logger.info("[" + getFullName() + "] - Initializing execution");

    main = new Executor();

    String cmd = node.get("command").asText();
    cmd = manageClustering(cmd);

    logger.info("[" + getFullName() + "] - Launching " + cmd);
    command = main.execute(cmd, getExecutionDirectory());

    //Wait for the grace period, or specific text
    grace();

    if (getClientCheck() != null) {
      logger.info("Executing client-check : " + getClientCheck());

      scriptRunner.setGlobalVariable("helper", new GroovyScriptHelper(run,
          main, client, run.base(), node));
      Map<String, Object> context = new LinkedHashMap<>();
      FileLogger lg = new FileLogger(new File(getExecutionDirectory(), "client-check.log"),
          logger);
      scriptRunner.run("client-check", run.base(), getClientCheck(), context, lg,
          "client-check", true);

    } else if (node.get("client-command") != null) {
      client = new Executor();
      final String clientCmd
          = node.get("client-command").asText().replace("${interface}", run.getInterface());
      logger.info("[" + getFullName() + "] - Launching client: " + clientCmd);
      clientCommand = client.execute(clientCmd, getExecutionDirectory());
      waitExecution();
    } else {
      waitExecution();
    }

    logger.info("[" + getFullName() + "] - Execution completed");

    Destroyer.INSTANCE.killThemAll();
    main.waitForTermination();
    if (client != null) {
      client.waitForTermination();
    }

    logger.info("[" + getFullName() + "] - Processes killed");

    if (getPostCheck() != null) {
      // Execute the post check
      logger.info("Executing post-check : " + getPostCheck());

      scriptRunner.setGlobalVariable("helper", new GroovyScriptHelper(run,
          main, client, run.base(), node));
      Map<String, Object> context = new LinkedHashMap<>();
      FileLogger lg = new FileLogger(new File(getExecutionDirectory(), "post-run.log"), logger);
      scriptRunner.run("post-run script", run.base(), getPostCheck(), context, lg,
          "post-run", true);
    }
  }

  private String manageClustering(String cmd) throws IOException {
    if (run.requireCluster()) {
      // Copy the cluster file to the working directory.
      Handlebars handlebars = new Handlebars();
      Template template = handlebars.compile("templates/cluster");
      final String itf = run.getInterface();
      Context context = Context.newContext(ImmutableMap.of("interface", itf));
      final String result = template.apply(context);
      File out = new File(getExecutionDirectory(), "cluster.xml");
      FileUtils.write(out, result);
      return cmd.replace("${interface}", itf);
    }
    return cmd;
  }


  public void cleanup() {
    if (Destroyer.INSTANCE.size() != 0) {
      Destroyer.INSTANCE.killThemAll();
      try {
        main.waitForTermination();
      } catch (IOException e) {
        // Ignore it.
      }
      if (client != null) {
        try {
          client.waitForTermination();
        } catch (IOException e) {
          // Ignore it.
        }
      }
    }
  }


  private void grace() {
    final String text = getGraceText();
    if (text != null) {
      Awaitility.await().atMost(1, TimeUnit.MINUTES).until(
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              return main.getOutput().contains(text)
                  || main.getError().contains(text);
            }
          }
      );
    } else {
      TimeUtils.sleep(getGracePeriod());
    }
  }

  private String getGraceText() {
    JsonNode n = node.get("grace-text");
    if (n != null) {
      return n.asText();
    }
    return run.getGraceText();
  }

  private int getGracePeriod() {
    if (node.get("grace_period") != null) {
      return node.get("grace_period").asInt();
    } else {
      return run.getGracePeriod();
    }
  }

  private void waitExecution() {
    String until = getExecuteUntil();
    String clientUntil = getClientExecuteUntil();
    if (until != null) {
      Awaitility.await().atMost(1, TimeUnit.MINUTES).until(
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              return main.getOutput().contains(until) || main.getError().contains(until);
            }
          }
      );
    } else if (clientUntil != null) {
      Awaitility.await().atMost(1, TimeUnit.MINUTES).until(
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              return client.getOutput().contains(clientUntil)
                  || client.getError().contains(clientUntil);
            }
          }
      );
    } else {
      if (node.get("execution_time") != null) {
        TimeUtils.sleep(node.get("execution_time").asInt());
      } else {
        run.waitExecutionTime();
      }
    }
  }

  private String getExecuteUntil() {
    if (node.get("execute-until") != null) {
      return node.get("execute-until").asText();
    }
    return run.getExecuteUntil();
  }

  private String getClientExecuteUntil() {
    if (node.get("client-execute-until") != null) {
      return node.get("client-execute-until").asText();
    }
    return run.getClientExecuteUntil();
  }

  public File getExecutionDirectory() {
    if (node.get("directory") != null) {
      return new File(node.get("directory").asText());
    } else {
      return run.base();
    }
  }

  public String getPostCheck() throws IOException {
    String name;
    if (node.get("post-check") != null) {
      name = node.get("post-check").asText();
    } else {
      name = run.getPostCheck();
    }

    if (name == null) {
      return null;
    }

    File script = new File(run.file().getParentFile(), name);
    if (!script.isFile()) {
      throw new IOException("Cannot find post check : " + script.getAbsolutePath());
    }
    return name;
  }

  public String getClientCheck() throws IOException {
    String name;
    if (node.get("client-check") != null) {
      name = node.get("client-check").asText();
    } else {
      name = run.getClientCheck();
    }

    if (name == null) {
      return null;
    }

    File script = new File(run.file().getParentFile(), name);
    if (!script.isFile()) {
      throw new IOException("Cannot find client check : " + script.getAbsolutePath());
    }
    return name;
  }

  public void markAsFailed(RunFailureException e) {
    status = Status.FAILURE;
    exception = e;
  }

  public void markAsError(Throwable e) {
    status = Status.ERROR;
    exception = e;
  }

  public void markAsSuccess() {
    status = Status.SUCCESS;
  }


  public String getStatus() {
    return status.name();
  }

  public String getOutput() {
    return main.getOutput();
  }

  public String getError() {
    return main.getError();
  }

  public long getTime() {
    return executionTime;
  }

  public String getException() {
    if (exception != null) {
      String error = ExceptionUtils.getMessage(exception);
      error += "\n";
      error += ExceptionUtils.getStackTrace(exception);

      final Throwable root = ExceptionUtils.getRootCause(exception);
      if (root != null) {
        error += "Root Cause: \n";
        error += ExceptionUtils.getRootCauseMessage(exception);
        error += "\n";
        error += ExceptionUtils.getStackTrace(root);
      }
      return error;
    }
    return null;
  }

  public String getRun() {
    return run.name();
  }

  public String getExecution() {
    return name;
  }

  public String getPostRun() throws IOException {
    File out = new File(getExecutionDirectory(), "post-run.log");
    if (out.isFile()) {
      return FileUtils.readFileToString(out);
    }
    return "no log";
  }

  public String getFile() {
    return run.file().getAbsolutePath();
  }

  public long getTotalExecutionTime() {
    return run.totalExecutionTime();
  }

  public String getCommand() {
    return command;
  }

  public String getClientCommand() {
    return clientCommand;
  }
}