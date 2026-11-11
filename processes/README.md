# Processes

BPMN process models and DMN decision tables, authored with Camunda Desktop Modeler. They live here rather than inside
the Spring resources because the process model is a first-class artefact ([ADR 0009](../docs/adr/0009-monorepo.md)).

| File | Contents |
| --- | --- |
| `provisio-forecast-batch.bpmn` | Process `provisio-forecast-batch`: the nightly and on-demand forecast batch |
| `provisio-forecast-run-assessment.dmn` | Decision `provisio-forecast-run-assessment`: whether a forecast run is usable |

## Deployment

The orchestrator deploys every `*.bpmn` and `*.dmn` file of this folder when it starts
(`ProcessDeploymentConfiguration`): the Maven build packages them on its classpath under `processes/`, and the Docker
build receives this folder as the named build context `processes`.

- **One deployment for all files.** A business rule task bound with `bindingType="deployment"` finds only a decision
  deployed together with its process, so BPMN and DMN must never be deployed separately.
- **Unchanged files create no new version**; a changed file becomes the latest version at the next start, and a new
  version of a process with a timer start event replaces the previous timer.
- **The timer is armed as soon as the orchestrator runs:** with the local stack up at 01:00 UTC, the forecast batch
  runs on its own.
- **A manual deployment is overwritten at the next restart** if the files here differ: the repository is the source of
  truth for the deployed models.
- The orchestrator does not start if no model is found here.

To try a change without restarting the orchestrator, deploy both files in one request from the repository root:

```bash
curl -s -X POST http://localhost:8080/v2/deployments \
  -F resources=@processes/provisio-forecast-batch.bpmn \
  -F resources=@processes/provisio-forecast-run-assessment.dmn
```

Deploying the process alone, as Desktop Modeler does with the open file, is rejected: its decision is not in the
same deployment.

## Starting the forecast batch by hand

Publish the start message; `asOf` must be in the variables as well, the correlation key does not become a variable:

```bash
curl -s -X POST http://localhost:8080/v2/messages/publication \
  -H 'Content-Type: application/json' \
  -d '{"name": "provisio.forecasting.batch-requested", "correlationKey": "2011-12-08", "timeToLive": 0,
       "variables": {"asOf": "2011-12-08"}}'
```
