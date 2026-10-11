# plugin-selection

Mounted read-only into the `minecraft-wrapper` container at `/etc/omcsi/plugins`
(`compose.yml`). On Kubernetes the same path is the optional `<release>-plugins`
ConfigMap instead (`minecraftWrapper.pluginSelection` in `helm/omcsi/values.yaml`).

Leave this directory without a `selection.json` and nothing changes. With one, every
container start reconciles `plugins/` against it (`reconcile_plugin_selection` in
`resources/post-create.sh`):

```json
{
  "version": 1,
  "plugins": [
    {"slug": "medieval-factions", "tag": "v5.8.1",
     "filename": "MedievalFactions-5.8.1-all.jar",
     "url": "https://example.org/plugins/MedievalFactions-5.8.1-all.jar"}
  ]
}
```

- A listed plugin missing from `plugins/` is downloaded and saved as `filename`.
- A listed plugin already present under another filename (uploaded by hand, or updated
  in-game) is adopted, not duplicated. Plugins are matched by the `name` in their
  `plugin.yml`, not by filename.
- A plugin that was installed or adopted from the selection and is no longer listed
  has its jar removed. Its data folder (`plugins/<Name>/`) is always kept.
- Jars the selection never installed or adopted are never touched.

The result of the last run is written to `plugins/.omcsi-applied.json` and served by
the wrapper at `GET /api/plugins/applied`.
