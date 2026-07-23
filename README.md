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

## Building

```console
flix build
flix test
```
