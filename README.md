# flix-lab

A small Flix command-line program that greets a name passed on the command
line.

## Usage

```console
flix run                # Hello World!
flix run -- Ada          # Hello, Ada!
flix run -- -h            # prints usage and exits
flix run -- --help        # same as -h
flix run -- --usage       # same as -h
```

| Flag                      | Description                      |
| ------------------------- | -------------------------------- |
| `-h`, `--help`, `--usage` | Show the usage message and exit. |

Passing an unrecognized option prints an error and the usage message, and
exits with status code 2.

## Datalog + Java interop demo

[src/DatalogYamlDemo.flix](src/DatalogYamlDemo.flix) is a second, independent
entry point (`demo`) that demonstrates:

* Reading a text file with plain Java IO (`java.nio.file.Files`), no
  third-party library.
* Building and populating `java.util.ArrayList`/`java.util.HashMap`
  structures directly from Flix to parse the file.
* [Injecting](https://doc.flix.dev/fixpoints.html#injecting-facts-into-datalog)
  the parsed facts into a Datalog program and querying it.

It reads [resources/people.yaml](resources/people.yaml), a simplified
YAML-like family tree of 74 people across 8 families that all descend from a
shared pair of common ancestors ("Adam" and "Eve"), and computes:

* `Father`/`Mother` -- each person's two parents, taken directly from the file.
* `Grandfather`/`Grandmother` -- each person's grandparents, joining
  `Father`/`Mother` one level up through either parent.
* `Ancestor` -- the transitive closure of "has a parent", i.e. every ancestor
  above a person, not just their immediate parents.

It also dumps the full tree, from the common ancestors down through every
descendant, using plain Flix recursion over the parsed facts (not Datalog).

Run it with:

```console
flix run --entrypoint demo
```

## Building

```console
flix build
flix test
```
