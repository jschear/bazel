---
layout: documentation
title: Toolchains
---

# Toolchains


This page describes the toolchain framework, which is a way for rule authors to
decouple their rule logic from platform-based selection of tools. It is
recommended to read the [rules][Rules] and [platforms][Platforms] pages before
continuing. This page covers why toolchains are needed, how to define and use
them, and how Bazel selects an appropriate toolchain based on platform
constraints.

## Motivation

Let's first look at the problem toolchains are designed to solve. Suppose you
are writing rules to support the "bar" programming language. Your `bar_binary`
rule would compile `*.bar` files using the `barc` compiler, a tool that itself
is built as another target in your workspace. Since users who write `bar_binary`
targets shouldn't have to specify a dependency on the compiler, you make it an
implicit dependency by adding it to the rule definition as a private attribute.

```python
bar_binary = rule(
    implementation = _bar_binary_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        ...
        "_compiler": attr.label(
            default = "//bar_tools:barc_linux",  # the compiler running on linux
            providers = [BarcInfo],
        ),
    },
)
```

`//bar_tools:barc_linux` is now a dependency of every `bar_binary` target, so
it'll be built before any `bar_binary` target. It can be accessed by the rule's
implementation function just like any other attribute:

```python
BarcInfo = provider(
    doc = "Information about how to invoke the barc compiler.",
    # In the real world, compiler_path and system_lib might hold File objects,
    # but for simplicity they are strings for this example. arch_flags is a list
    # of strings.
    fields = ["compiler_path", "system_lib", "arch_flags"],
)

def _bar_binary_impl(ctx):
    ...
    info = ctx.attr._compiler[BarcInfo]
    command = "%s -l %s %s" % (
        info.compiler_path,
        info.system_lib,
        " ".join(info.arch_flags),
    )
    ...
```

The issue here is that the compiler's label is hardcoded into `bar_binary`, yet
different targets may need different compilers depending on what platform they
are being built for and what platform they are being built on -- called the
*target platform* and *execution platform*, respectively. Furthermore, the rule
author does not necessarily even know all the available tools and platforms, so
it is not feasible to hardcode them in the rule's definition.

A less-than-ideal solution would be to shift the burden onto users, by making
the `_compiler` attribute non-private. Then individual targets could be
hardcoded to build for one platform or another.

```python
bar_binary(
    name = "myprog_on_linux",
    srcs = ["mysrc.bar"],
    compiler = "//bar_tools:barc_linux",
)

bar_binary(
    name = "myprog_on_windows",
    srcs = ["mysrc.bar"],
    compiler = "//bar_tools:barc_windows",
)
```

You can improve on this solution by using `select` to choose the `compiler`
[based on the platform][Configurable attributes]:

```python
config_setting(
    name = "on_linux",
    constraint_values = [
        "@platforms//os:linux",
    ],
)

config_setting(
    name = "on_windows",
    constraint_values = [
        "@platforms//os:windows",
    ],
)

bar_binary(
    name = "myprog",
    srcs = ["mysrc.bar"],
    compiler = select({
        ":on_linux": "//bar_tools:barc_linux",
        ":on_windows": "//bar_tools:barc_windows",
    }),
)
```

But this is tedious and a bit much to ask of every single `bar_binary` user. If
this style is not used consistently throughout the workspace, it leads to builds
that work fine on a single platform but fail when extended to multi-platform
scenarios. It also does not address the problem of adding support for new
platforms and compilers without modifying existing rules or targets.

The toolchain framework solves this problem by adding an extra level of
indirection. Essentially, you declare that your rule has an abstract dependency
on *some* member of a family of targets (a toolchain type), and Bazel
automatically resolves this to a particular target (a toolchain) based on the
applicable platform constraints. Neither the rule author nor the target author
need know the complete set of available platforms and toolchains.

## Writing rules that use toolchains

Under the toolchain framework, instead of having rules depend directly on tools,
they instead depend on *toolchain types*. A toolchain type is a simple target
that represents a class of tools that serve the same role for different
platforms. For instance, you can declare a type that represents the bar
compiler:

```python
# By convention, toolchain_type targets are named "toolchain_type" and
# distinguished by their package path. So the full path for this would be
# //bar_tools:toolchain_type.
toolchain_type(name = "toolchain_type")
```

The rule definition in the previous section is modified so that instead of
taking in the compiler as an attribute, it declares that it consumes a
`//bar_tools:toolchain_type` toolchain.

```python
bar_binary = rule(
    implementation = _bar_binary_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        ...
        # No `_compiler` attribute anymore.
    },
    toolchains = ["//bar_tools:toolchain_type"]
)
```

The implementation function now accesses this dependency under `ctx.toolchains`
instead of `ctx.attr`, using the toolchain type as the key.

```python
def _bar_binary_impl(ctx):
    ...
    info = ctx.toolchains["//bar_tools:toolchain_type"].barcinfo
    # The rest is unchanged.
    command = "%s -l %s %s" % (
        info.compiler_path,
        info.system_lib,
        " ".join(info.arch_flags),
    )
    ...
```

`ctx.toolchains["//bar_tools:toolchain_type"]` returns the
[`ToolchainInfo` provider][ToolchainInfo] of whatever target Bazel resolved the
toolchain dependency to. The fields of the `ToolchainInfo` object are set by the
underlying tool's rule; in the next section, this rule is defined such that
there is a `barcinfo` field that wraps a `BarcInfo` object.

Bazel's procedure for resolving toolchains to targets is described
[below](#toolchain-resolution). Only the resolved toolchain target is actually
made a dependency of the `bar_binary` target, not the whole space of candidate
toolchains.

### Writing aspects that use toolchains

Aspects have access to the same toolchain API as rules: you can define required
toolchain types, access toolchains via the context, and use them to generate new
actions using the toolchain.

```py
bar_aspect = aspect(
    implementation = _bar_aspect_impl,
    attrs = {},
    toolchains = ['//bar_tools:toolchain_type'],
)

def _bar_aspect_impl(target, ctx):
  toolchain = ctx.toolchains['//bar_tools:toolchain_type']
  # Use the toolchain provider like in a rule.
  return []
```

## Defining toolchains

To define some toolchains for a given toolchain type, you need three things:

1.  A language-specific rule representing the kind of tool or tool suite. By
    convention this rule's name is suffixed with "\_toolchain".

    1.  **Note:** The `\_toolchain` rule cannot create any build actions.
        Rather, it collects artifacts from other rules and forwards them to the
        rule that uses the toolchain. That rule is responsible for creating all
        build actions.

2.  Several targets of this rule type, representing versions of the tool or tool
    suite for different platforms.

3.  For each such target, an associated target of the generic
    [`toolchain`][Toolchain rule] rule, to provide metadata used by the
    toolchain framework. This `toolchain` target also refers to the
    `toolchain_type` associated with this toolchain. This means that a given
    `_toolchain` rule could be associated with any `toolchain_type`, and that
    only in a `toolchain` instance that uses this `_toolchain` rule that the
    rule is associated with a `toolchain_type`.

For our running example, here's a definition for a `bar_toolchain` rule. Our
example has only a compiler, but other tools such as a linker could also be
grouped underneath it.

```python
def _bar_toolchain_impl(ctx):
    toolchain_info = platform_common.ToolchainInfo(
        barcinfo = BarcInfo(
            compiler_path = ctx.attr.compiler_path,
            system_lib = ctx.attr.system_lib,
            arch_flags = ctx.attr.arch_flags,
        ),
    )
    return [toolchain_info]

bar_toolchain = rule(
    implementation = _bar_toolchain_impl,
    attrs = {
        "compiler_path": attr.string(),
        "system_lib": attr.string(),
        "arch_flags": attr.string_list(),
    },
)
```

The rule must return a `ToolchainInfo` provider, which becomes the object that
the consuming rule retrieves using `ctx.toolchains` and the label of the
toolchain type. `ToolchainInfo`, like `struct`, can hold arbitrary field-value
pairs. The specification of exactly what fields are added to the `ToolchainInfo`
should be clearly documented at the toolchain type. In this example, the values
return wrapped in a `BarcInfo` object to reuse the schema defined above; this
style may be useful for validation and code reuse.

Now you can define targets for specific `barc` compilers.

```python
bar_toolchain(
    name = "barc_linux",
    arch_flags = [
        "--arch=Linux",
        "--debug_everything",
    ],
    compiler_path = "/path/to/barc/on/linux",
    system_lib = "/usr/lib/libbarc.so",
)

bar_toolchain(
    name = "barc_windows",
    arch_flags = [
        "--arch=Windows",
        # Different flags, no debug support on windows.
    ],
    compiler_path = "C:\\path\\on\\windows\\barc.exe",
    system_lib = "C:\\path\\on\\windows\\barclib.dll",
)
```

Finally, you create `toolchain` definitions for the two `bar_toolchain` targets.
These definitions link the language-specific targets to the toolchain type and
provide the constraint information that tells Bazel when the toolchain is
appropriate for a given platform.

```python
toolchain(
    name = "barc_linux_toolchain",
    exec_compatible_with = [
        "@platforms//os:linux",
        "@platforms//cpu:x86_64",
    ],
    target_compatible_with = [
        "@platforms//os:linux",
        "@platforms//cpu:x86_64",
    ],
    toolchain = ":barc_linux",
    toolchain_type = ":toolchain_type",
)

toolchain(
    name = "barc_windows_toolchain",
    exec_compatible_with = [
        "@platforms//os:windows",
        "@platforms//cpu:x86_64",
    ],
    target_compatible_with = [
        "@platforms//os:windows",
        "@platforms//cpu:x86_64",
    ],
    toolchain = ":barc_windows",
    toolchain_type = ":toolchain_type",
)
```

The use of relative path syntax above suggests these definitions are all in the
same package, but there's no reason the toolchain type, language-specific
toolchain targets, and `toolchain` definition targets can't all be in separate
packages.

See the [`go_toolchain`][Go toolchain] for a real-world example.

### Toolchains and configurations

An important question for rule authors is, when a `bar_toolchain` target is
analyzed, what [configuration][Configuration] does it see, and what transitions
should be used for dependencies? The example above uses string attributes, but
what would happen for a more complicated toolchain that depends on other targets
in the Bazel repository?

Let's see a more complex version of `bar_toolchain`:

```python
def _bar_toolchain_impl(ctx):
    # The implementation is mostly the same as above, so skipping.
    pass

bar_toolchain = rule(
    implementation = _bar_toolchain_impl,
    attrs = {
        "compiler": attr.label(
            executable = True,
            mandatory = True,
            cfg = "exec",
        ),
        "system_lib": attr.label(
            mandatory = True,
            cfg = "target",
        ),
        "arch_flags": attr.string_list(),
    },
)
```

The use of [`attr.label`][Label attributes] is the same as for a standard rule,
but the meaning of the `cfg` parameter is slightly different.

The dependency from a target (called the "parent") to a toolchain via toolchain
resolution uses a special configuration transition called the "toolchain
transition". The toolchain transition keeps the configuration the same, except
that it forces the execution platform to be the same for the toolchain as for
the parent (otherwise, toolchain resolution for the toolchain could pick any
execution platform, and wouldn't necessarily be the same as for parent). This
allows any `exec` dependencies of the toolchain to also be executable for the
parent's build actions. Any of the toolchain's dependencies which use `cfg =
"target"` (or which don't specify `cfg`, since "target" is the default) are
built for the same target platform as the parent. This allows toolchain rules to
contribute both libraries (the `system_lib` attribute above) and tools (the
`compiler` attribute) to the build rules which need them. The system libraries
are linked into the final artifact, and so need to be built for the same
platform, whereas the compiler is a tool invoked during the build, and needs to
be able to run on the execution platform.

## Registering and building with toolchains

At this point all the building blocks are assembled, and you just need to make
the toolchains available to Bazel's resolution procedure. This is done by
registering the toolchain, either in a `WORKSPACE` file using
`register_toolchains()`, or by passing the toolchains' labels on the command
line using the `--extra_toolchains` flag.

```python
register_toolchains(
    "//bar_tools:barc_linux_toolchain",
    "//bar_tools:barc_windows_toolchain",
    # Target patterns are also permitted, so you could have also written:
    # "//bar_tools:all",
)
```

Now when you build a target that depends on a toolchain type, an appropriate
toolchain will be selected based on the target and execution platforms.

```python
# my_pkg/BUILD

platform(
    name = "my_target_platform",
    constraint_values = [
        "@platforms//os:linux",
    ],
)

bar_binary(
    name = "my_bar_binary",
    ...
)
```

```sh
bazel build //my_pkg:my_bar_binary --platforms=//my_pkg:my_target_platform
```

Bazel will see that `//my_pkg:my_bar_binary` is being built with a platform that
has `@platforms//os:linux` and therefore resolve the
`//bar_tools:toolchain_type` reference to `//bar_tools:barc_linux_toolchain`.
This will end up building `//bar_tools:barc_linux` but not
`//bar_tools:barc_windows`.

## Toolchain resolution

**Note:** [Some Bazel rules][Rules status] do not yet support toolchain
resolution.

For each target that uses toolchains, Bazel's toolchain resolution procedure
determines the target's concrete toolchain dependencies. The procedure takes as
input a set of required toolchain types, the target platform, the list of
available execution platforms, and the list of available toolchains. Its outputs
are a selected toolchain for each toolchain type as well as a selected execution
platform for the current target.

The available execution platforms and toolchains are gathered from the
`WORKSPACE` file via
[`register_execution_platforms`][Workspace function register_execution_platforms]
and [`register_toolchains`][Workspace function register_toolchains]. Additional
execution platforms and toolchains may also be specified on the command line via
[`--extra_execution_platforms`][Flag extra_execution_platforms] and
[`--extra_toolchains`][Flag extra_toolchains]. The host platform is
automatically included as an available execution platform. Available platforms
and toolchains are tracked as ordered lists for determinism, with preference
given to earlier items in the list.

The resolution steps are as follows.

1.  A `target_compatible_with` or `exec_compatible_with` clause *matches* a
    platform iff, for each `constraint_value` in its list, the platform also has
    that `constraint_value` (either explicitly or as a default).

    If the platform has `constraint_value`s from `constraint_setting`s not
    referenced by the clause, these do not affect matching.

1.  If the target being built specifies the
    [`exec_compatible_with` attribute][Common exec_compatible_with attribute]
    (or its rule definition specifies the
    [`exec_compatible_with` argument][Rule exec_compatible_with argument]) the
    list of available execution platforms is filtered to remove any that do not
    match the execution constraints.

1.  For each available execution platform, you associate each toolchain type
    with the first available toolchain, if any, that is compatible with this
    execution platform and the target platform.

1.  Any execution platform that failed to find a compatible toolchain for one of
    its toolchain types is ruled out. Of the remaining platforms, the first one
    becomes the current target's execution platform, and its associated
    toolchains become dependencies of the target.

The chosen execution platform is used to run all actions that the target
generates.

In cases where the same target can be built in multiple configurations (such as
for different CPUs) within the same build, the resolution procedure is applied
independently to each version of the target.

## Debugging toolchains

If you are adding toolchain support to an existing rule, use the
`--toolchain_resolution_debug=regex` flag. During toolchain resolution, the flag
provides verbose output for toolchain types or target names that match the regex
variable. You can use `.*` to output all information. Bazel will output names of
toolchains it checks and skips during the resolution process.

If you'd like to see which [`cquery`][cquery] dependencies are from toolchain
resolution, use `cquery`'s [`--transitions`][cquery-transitions] flag:

```
# Find all direct dependencies of //cc:my_cc_lib. This includes explicitly
# declared dependencies, implicit dependencies, and toolchain dependencies.
$ bazel cquery 'deps(//cc:my_cc_lib, 1)'
//cc:my_cc_lib (96d6638)
@bazel_tools//tools/cpp:toolchain (96d6638)
@bazel_tools//tools/def_parser:def_parser (HOST)
//cc:my_cc_dep (96d6638)
@local_config_platform//:host (96d6638)
@bazel_tools//tools/cpp:toolchain_type (96d6638)
//:default_host_platform (96d6638)
@local_config_cc//:cc-compiler-k8 (HOST)
//cc:my_cc_lib.cc (null)
@bazel_tools//tools/cpp:grep-includes (HOST)

# Which of these are from toolchain resolution?
$ bazel cquery 'deps(//cc:my_cc_lib, 1)' --transitions=lite | grep "toolchain dependency"
  [toolchain dependency]#@local_config_cc//:cc-compiler-k8#HostTransition -> b6df211
```

[Common exec_compatible_with attribute]: be/common-definitions.html#common.exec_compatible_with
[Configurable attributes]: configurable-attributes.html
[Configuration]: glossary.html#configuration
[cquery]: cquery.html
[cquery-transitions]: cquery.html#transitions
[Flag extra_execution_platforms]: command-line-reference.html#flag--extra_execution_platforms
[Flag extra_toolchains]: command-line-reference.html#flag--extra_toolchains
[Go toolchain]: https://github.com/bazelbuild/rules_go/blob/master/go/private/go_toolchain.bzl
[Label attributes]: skylark/lib/attr.html#label
[Platforms]: platforms.html
[Rule exec_compatible_with argument]: skylark/lib/globals.html#rule.exec_compatible_with
[Rules]: skylark/rules.html
[Rules status]: platforms-intro.html#status
[ToolchainInfo]: skylark/lib/platform_common.html#ToolchainInfo
[Toolchain rule]: be/platform.html#toolchain
[Workspace function register_execution_platforms]: skylark/lib/globals.html#register_execution_platforms
[Workspace function register_toolchains]: skylark/lib/globals.html#register_toolchains
