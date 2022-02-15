# Documenting Targets

Flowman also supports documenting build targets.

## Example

```yaml
targets:
  stations:
    kind: relation
    description: "Write stations"
    mapping: stations_raw
    relation: stations
    
    documentation:
      description: "This build target is used to write the weather stations"
```