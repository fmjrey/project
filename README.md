# fmjrey/project

Utility library to support a `:project` entry in `deps.edn`.

**WORK IN PROGRESS**

## Rationale

As the clojure cli and build tools mature and become prevalent, the feature gap
with the previous leiningen tool is diminishing. Capturing project metadata
such as name and version remains however unhandled, and there aren't many places
where it can.

[Tools.build](https://clojure.org/guides/tools_build) offers APIs that help
with versioning, and thus may seem like a good place to start. Except projects
do not necessarily need to use it, or have a file named `build.clj`.
Also `build.clj` isn't so much about data, and more about logic, though it needs
that data.
Then there is `deps.edn` which mostly deals with dependencies and does not say
much about the dependent side (e.g. `:paths`). It could say more, the verb
_to depend_ is transitive and requires a dependent subject and one or more
direct dependency objects.
A major hurdle at present is that neither are guaranteed to be present
in a release artifact, certainly not `build.clj` as many projects don't need
one, or even are required to name it so. The trend to use direct git coordinates
may provide a way to access `deps.edn`, but then at runtime the root project
directory isn't normally included on the classpath (TODO: validate).

Still, having a well defined place to capture project metadata helps
tremendously different uses cases, such as discovery and tooling.
The recent creation of
[tools.deps.edn](https://github.com/clojure/tools.deps.edn),
JIRA [TDEPS-277](https://clojure.atlassian.net/browse/TDEPS-277)
and [TDEPS-278](https://clojure.atlassian.net/browse/TDEPS-278),
seem to point towards a future where a project `deps.edn` might be made
available more systematically regardless of the situation.

In terms of design there are some special cases to consider:

1. Q: If both `deps.edn` in project root and resource directory are available,
   which one is loaded?
   
   A: Load from project root first, and if not found try as a resource.
2. Q: How to ensure a library is loading its own `deps.edn` and not the one
   from another library or even the dependent application?

   A: Store an `deps.edn` copy into the `deps/<group-id>/<artifact-id>/`
   resource directory. Search `deps.edn` first in project root then in
   the resource directory, accepting the first that matches a given
   `groupId/artifactId`.
3. Q: Can a source dependency (git or local) access its own `deps.edn`
   from within its code?

   A: TBD. A dependency provided as a jar is not guaranteed to have its
   `deps.edn` packaged along the code, whereas its own resources certainly are.
   Source dependencies however should only have a `deps.edn` in project root
   since the resource copy is not supposed to be checked in. Moreover accessing
   resources is very much dependent on the classloading strategy which may vary
   from one context to the other (e.g. uberjar, Spring, etc.). Nevertheless the
   expectation for source dependencies is that there should always be a way to
   load `deps.edn` from project root, though some experimentation is needed to
   validate the most reliable way to do that.

This library is therefore an experiment to use `deps.edn` as the place where
project data is captured into a new `:project` entry. It also provides a way to
retrieve it at runtime by copying `deps.edn` into a resource directory.
The library is written in a way that should make it reasonably easy to merge with
[tools.deps.edn](https://github.com/clojure/tools.deps.edn).

## deps.edn :project entry

The `:project` entry in `deps.edn` contains a single map of data about the
the project such as version, name, license, etc. Only the version in a project
root should be edited directly and version controlled. The copy that is made in
a resource directory should not be edited and preferably not checked in.

### :project entries

In `deps.edn` project related data about the dependent side should be placed
in a map under `:project` with the following entries:

- `:id`: a qualified symbol identifying the project, as expected under `:lib` by
        [write-pom](https://clojure.github.io/tools.build/clojure.tools.build.api.html#var-write-pom)
        and similar to the name defined with `defproject` in leiningen.
- `:version-string`: the full version identifier as a string, typically
  computed from the `:version` and `:source` entries
- `:description`: a short description of the project
- `:url`: the home URL of the project
- `:license`: a map, or vector of maps in case of multiple licenses, containing
  license information with the following keys:
  - `:id`: the [SPDX](https://spdx.dev/) identifier of the license, if any
  - `:name`: the name of the license
  - `:url`: the URL of the license
- `:version`: map containing internal version information, typically:
  - `:major`: the major version number as an integer
  - `:minor`: the minor version number as an integer
  - `:patch`: the patch version number as an integer, if any
- `:source`: map containing coordinates for the project source, including:
  - `:url`: the URL where to find the source code, such as a git repository
  - `:rev`: the revision reference for this release, which in the case of git
    could be anything that `git rev-parse` can understand such as tags,
    abbreviated or full commit hashes, or even branch names.

Of these keys only `:id` is mandatory, though others are strongly recommended as
they help tooling and discovery. In particular `:version-string` is highly
recommended, so that it can be discovered at runtime and used for logging,
greeting and diagnostic printing, etc.

Entries in the `:version` map are not normative, only suggested. The intent is
to capture the most significant data to compute a full version so it remains
consistent, editable, and does not always require parsing to extract useful
information (great for tooling).

Entries in the `:source` map are not normative either. While for dependencies
there is a need to be very precise on how to fetch source code, the goal here
is not to fetch the code, but to be informative enough for humans and tooling
such as versioning. The idea is to not be specific to any VCS so keys should
be considered generic, e.g. `:rev` refers to the concept of revision that is
applicable to most VCS. Also in the unlikely but possible situation where the
source isn't provided as a repository, the `:url` could point to a downloadable
archive or a page where it can be found. The alternative to generic keys is to
use specific ones like those for git coordinates in `:deps`. You are free to do
that if that makes more sense for your use case.

Projects may add additional entries as needed. For example the versioning logic
could rely on such additional key:

- `:version-pattern`: the `format` pattern for creating the `version-string`
  along with an argument list referencing other keys in the `:project` map
  using the syntax `["format-pattern" :single-key [:submap :key]...]`, e.g.:
  `["v\\d+\\.\\d+\\.\\d+-\\s" [:version :major] [:version :minor]
                              [:source :rev-count] [:source :sha]]`

The above is not suggesting to store SHA and revision count inside `deps.edn`.
In fact values that quickly change over time such as these are discouraged
unless you have automation to always keep them up to date. However one could
imagine the project map to be augmented at runtime with these entries in order
to provide them to the next build step.

## Usage

This library can be used in 3 different ways:

1. at runtime as a library in order to read `deps.edn` wherever it may be
2. within your project `build.clj` or equivalent
3. as a clojure CLI command with the -T option

These require the following dependency declaration in your `deps.edn`:

```clojure
fmjrey/project {:git/tag "TBD" :git/sha "TBD"}
```

In all cases the require entry should be as follows:

```clojure
[fmjrey/project :as project]
```

### Use as a runtime library

Having access to project data can be useful for printing or logging information
at runtime, e.g. a header string containing name and version upon startup.

The easiest way to load project data is to use the macro `project/project-info`
in order to define a var containing project information, .e.g:

```clojure
(def app-info (project/project-info 'my/app))
```

Alternatively you can use the following functions that take an options map
that must have a `:lib` entry in the format `groupId/artifactId`:

- `read-deps`: loads the project data from either `deps.edn` in project root
  or its copy as a resource, whichever is found first and matches the given
  lib symbol to make sure it finds the right `deps.edn` file. Returns the given
  option map augmented with a `:project` entry.
- `read-all-deps`: load project data from `deps.edn` using various combinations
  of paths, file, and resource loading, returning a sequence of options maps
  augmented with a `:project` entry, among others. Useful for testing and
  experimentation.

### Use within build.clj

The most typical use within `build.clj` is to create tasks related to project
versioning and release. The following task function are provided, they each
take an options map as single argument that must contain a `:lib` entry in the
format `groupId/artifactId`. They return that hash map unchanged unless
otherwise stated.

- `copy-deps`: copy the project root `deps.edn` to a resource directory.
  Options map must have a `:lib` entry in the format `groupId/artifactId`
  (TODO).
- `read-deps` and `read-all-deps`: same as above.

### Use from clojure CLI

Functions mentioned in the previous section can also be run using the clojure
CLI with the -T option:

```
# copy project deps.edn to the resource directory
clojure -T:build fmjrey.project/copy-deps :lib myorg/mylib
# read project deps.edn
clojure -T:build fmjrey.project/read-deps :lib myorg/mylib
```

## Development (TODO)

Invoke a library API function from the command-line:

    $ clojure -X fmjrey.project/foo :a 1 :b '"two"'
    {:a 1, :b "two"} "Hello, World!"

Run the project's tests (they'll fail until you edit them):

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR (this will fail until you edit the tests to pass):

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to net.clojars.fmjrey/project on clojars.org by default.

## License

Copyright © 2026 François Rey

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)
