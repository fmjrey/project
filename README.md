# fmjrey/project

Utility library to capture project info in `deps.edn`.

**EXPERIMENTAL WORK IN PROGRESS**

## Rationale

As the clojure CLI and build tools mature and become prevalent, the feature gap
with the previous leiningen tool is diminishing. Capturing project metadata
such as name and version remains however unhandled, and there aren't many places
where it can.

[Tools.build](https://clojure.org/guides/tools_build) offers APIs that help
with versioning, and thus may seem like a good place to start. Except projects
do not necessarily need a build file, or have a it named `build.clj`.
Also `build.clj` isn't so much about data, and more about logic, though it most
likely needs project data to carry its work.
Then there is `deps.edn` which mostly deals with dependencies and does not say
much about the dependent side (e.g. `:paths`). It could say more, the verb
_to depend_ is transitive and requires a dependent subject and one or more
direct dependency objects.
A major hurdle at present is that neither are guaranteed to be present
in a release artifact, certainly not `build.clj` as many projects don't need
one, or even are required to name it so. The trend to use direct git coordinates
may provide a way to access `deps.edn`, and in any case a jar does not usually
contain the `deps.edn` unless it's copied in a resource directory before a build.

Still, having a well defined place to capture project metadata helps
tremendously different uses cases, such as discovery and tooling.
The recent creation of
[tools.deps.edn](https://github.com/clojure/tools.deps.edn),
JIRA [TDEPS-277](https://clojure.atlassian.net/browse/TDEPS-277)
and [TDEPS-278](https://clojure.atlassian.net/browse/TDEPS-278),
seem to point towards a future where a project `deps.edn` might be made
available more systematically regardless of the situation.

In terms of design here are some important considerations:

1. Q: Where in `deps.edn` should project info be captured?

   A: For now adding data within an alias is the recommended way to add user
   keys in `deps.edn`. The core team prefers to reserve top level keys for
   future evolution and cannot guarantee future access to custom keys, see the
   official documentation about this
   [here](https://clojure.org/reference/clojure_cli#_using_aliases_for_custom_purposes).
   That being said, the most logical place for project data would be at the
   same level as `:paths` and `:deps` since these already capture data about
   a project. Even with an alias, there is no guarantee its name won't
   conflict with other user aliases. Therefore the suggestion is to use a
   default qualified alias, say `:project/info`, and allow for it to be
   changed via some option.

2. Q: How is this data going to be merged with root and user `deps.edn`?

   A: Project data shouldn't be defined elsewhere than in the project root
   `deps.edn`. There are two different merge strategies being applied, the
   first one being the most constraining for a `:project/info` alias:
   - First step: combine all the different root, user, project, and extra
     `deps.edn` into a single one using `clojure.core/merge`. In other words the
     last entry (the most specific one on the left) replaces the previous ones
     (the most generic one on the right), which means the last alias wins, as
     documented [here](https://clojure.org/reference/clojure_cli#deps_sources).
   - Second step: use that combined `deps.edn` to reduce the aliases supplied to
     the CLI into a [runtime basis](https://clojure.org/reference/deps_edn#basis)
     so as to build a classpath, using per-key merge rules, and as documented
     [here](https://clojure.org/reference/clojure_cli#aliases).

3. Q: Can the [runtime basis](https://clojure.org/reference/deps_edn#basis)
   be used to retrieve project data?

   A: Yes but only to retrieve the dependent project data when launched via the
   [clojure CLI](https://clojure.org/guides/deps_and_cli). If a library used as a
   dependency would like to report on its own data (not on the dependent project)
   it needs to access its own `deps.edn`, which is why this file is copied as a
   resource because most logic for building a jar do not include it by default.
   Also there may be cases where the clojure CLI isn't used to launch a project,
   which is likely to happen with clojure dialects that still capture data in
   `deps.edn` but use other means to launch a project or script.

4. Q: If both `deps.edn` in project root and a copy in a resource directory
   are available, which one is loaded?

   A: Load from project root first, and if not found try as a resource.

5. Q: How to ensure a library is loading its own `deps.edn` and not the one
   from another library or even from the dependent application?

   A: Copy the `deps.edn` file into the `deps/<group-id>/<artifact-id>/`
   resource directory. Search `deps.edn` first in project root then in
   the resource directory, accepting the first that matches a given
   `groupId/artifactId`.

6. Q: Can a source dependency (git or local) access its own project `deps.edn`
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
project data is captured into a new `:project/info` alias. It also provides
a way to retrieve it at runtime from a jar by copying `deps.edn` into a resource
directory. The library is written in a way that should make it reasonably easy
to merge with [tools.deps.edn](https://github.com/clojure/tools.deps.edn).

## deps.edn :project/info alias

The `:project/info` alias in `deps.edn` contains a single map of data about
the project such as version, name, license, etc. Only the version in a project
root should be edited directly and version controlled. The copy that is made
in a resource directory should not be edited and preferably not checked in.

### :project/info alias entries

In `deps.edn`, project related data about the dependent side should be placed
in a map under the `:project/info` alias, and containing the following entries:

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
  - `:patch`: the patch version number as an integer
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
information.

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

- `:version-pattern`: the `format` pattern for creating the `:version-string`
  along with an argument list referencing other keys in the `:project/info` map
  using the syntax `["format-pattern" :single-key [:submap :key]...]`, e.g.:
  ```clojure
  ["v\\d+\\.\\d+\\.\\d+-\\s" [:version :major] [:version :minor]
                             [:source :rev-count] [:source :sha]]
  ```

The above is not suggesting to store SHA and revision count inside `deps.edn`.
In fact values that quickly change over time such as these are discouraged in
`:project/info`unless you have automation to always keep them up to date.
However one could imagine the project map to be augmented at runtime with these
time-dependent entries in order to provide them to the next build step.

Finally, in the rare case where a different alias name needs to be used to
capture project info, the `:fmjrey.project/alias` option can be set to the alias
keyword where to find project data, which defaults to `:project/info`.

## Usage

This library can be used in 3 different ways:

1. at runtime as a library in order to read project info from `deps.edn`
   wherever it may be
2. within your project `build.clj` or equivalent
3. as a clojure CLI command with the -T or -X options

At runtime there is no need to load the clojure build API, which is only really
needed for copying the `deps.edn` file to the resource directory. Therefore two
separate namespaces are offered, `fmjrey.project` for runtime use, and
`fmjrey.project.build` for build and CLI usage, which refers to some functions
in the first for convenience.
These require the following dependency declaration in your project `deps.edn`:

```clojure
{:deps {fmjrey/project {:git/tag "TAG" :git/sha "SHA"}} ;; runtime use
 :aliases {
   ;; easier to find as the first alias
   :project/info {
     :id: my.app/name
     :name "my app name"
     :license {
       :id "EPL-2.0"
       :name "Eclipse Public License 2.0"
       :url "https://www.eclipse.org/legal/epl-2.0"}}
   ;; CLI use
   :project {:deps {io.github.clojure/tools.build {:git/tag "TAG" :git/sha "SHA"}
                    org.clojure/tools.deps.edn {:mvn/version "0.9.22"} ; or later
                    fmjrey/project {:git/tag "TAG" :git/sha "SHA"}}
             :exec-args {:fmjrey.project/verbose true}
             :ns-default fmjrey.project.build}
   ;; build and task use
   :build {:deps {io.github.clojure/tools.build {:git/tag "TAG" :git/sha "SHA"}
                  org.clojure/tools.deps.edn {:mvn/version "0.9.22"} ; or later
                  fmjrey/project {:git/tag "TAG" :git/sha "SHA"}}
           :ns-default build}}}
```

Note how the above `deps.edn` example defines 2 similar aliases:

- `:project`: for easier CLI use as it defaults to the `fmjrey.project.build`
  namespace and adds the verbose option (otherwise nothing is printed),
- `:build`: for use in `build.clj`.

### Use as a runtime library

Having access to project data can be useful for printing or logging information
at runtime, e.g. a header string containing name and version upon startup.
Most situations will only need the `project-info` macro to retrieve a project
info. Additional functions are provided for more customized behavior and
experimentation.

In all cases the require entry should be as follows for runtime use:

```clojure
[fmjrey.project :as project]
```

#### Runtime `project-info` macro

The easiest way to load project data is to use the macro `project/project-info`
in order to define a var to capture the `:project/info` alias map, .e.g:

```clojure
(def app-info (project/project-info 'my.app/name))
```

The locations where project data is searched for are detailed in the
[Searched locations](searched-locations) section.
The search logic stops and returns the first project data found with an `:id`
matching the symbol given as single argument, or given under the `:lib` entry
within an options map also passed as single argument:

```clojure
(def app-info (project/project-info {:lib 'my.app/name}))
```
When no symbol is given to `project-info` it can only search project data for the
running application in its runtime basis and project root directory and not in
the project specific resource directory. It will also return the first project
data found regardless of its `:id`. For a more deterministic outcome it is best
to provide a symbol argument, and certainly necessary in the case of a library
code wishing to load its own project data instead of the dependent project data.

The search logic also tries to load a resource without specifying any
classloader, and then tries with an optional classloader if given with the
`:fmjrey.project/loader` option. The `project-info` macro adds the caller
classloader automatically, if not already provided, while other non-macro API
functions detailed below don't.

A list of all possible options is detailed in the [Options](#options) section.

#### Runtime functions

In addition to the `project-info` macro the following functions take an options
map that may or may not have a `:lib` entry in the format `groupId/artifactId`.
They apply the same search logic as explained above for the macro, and return
the given option map possibly augmented with a `:project/info` entry if a
matching one is found.

- `read-project`: returns the given option map augmented with a `:project/info`
  entry if found, and stripped from all keys qualified with `fmjrey.project`.
- `searched-deps`: list all `deps.edn` files or resource files checked
  for a matching project entry. This is mostly for experimentation and testing
  so as to check the different combinations of paths, files, and resources that
  are checked. Returns a sequence of options maps augmented with various
  working keys and a `:project/info` entry when a matching id is found.

For experimentation `print-project` and `print-searched-deps` offer exactly
the same functionality as their above counterparts, except they print the
locations where a matching project entry has been searched for.
To also print the matching project entries add `:fmjrey.project/verbose :very`
to the options map. Printing is in fact controlled by this option which is set
to `true` by the printing functions (unless `:very` is passed).

A list of all possible options is detailed in the [Options](#options) section.

### Use within build.clj

The most typical use within `build.clj` is to create tasks related to project
versioning and release.

The require entry should be as follows:

```clojure
[fmjrey.project.build :as project]
```

For convenience most of the public functions from the `fmjrey.project` namespace
are also available in the `fmjrey.project.build` namespace, e.g. `read-project`,
`searched-deps`, and their printing alternate are the same, no need to require
`fmjrey.project`, and their above description also apply here.
One additional function however is available only in the `fmjrey.project.build`
namespace:

- `copy-deps`: copy the project root `deps.edn` to a resource directory.
  Options map must have a `:lib` entry in the format `groupId/artifactId`
  and an optional `:fmjrey.project/resdir` to specify the destination resource
  directory (defaults to `"resources"`). The
  [Current basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/current-basis)
  is checked for a custom project root and `deps.edn` path in order to determine
  the path to the project `deps.edn` when not in the project root directory.
  Return the options map unchanged.
  **TODO:** add ability to also use `tools.deps.edn/project-deps-path` API
   ([doc](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps-path))
   so that `clojure.tools.deps.util.dir/with-dir` may be used to specify a
   custom project directory to determine source and destination. This should be
   controlled by an option. **What default behavior should this option have?**

### Use from clojure CLI

Functions mentioned in the previous section can also be run using the clojure
CLI with the -T option:

```
# copy project deps.edn to the resource directory
clojure -T:project copy-deps :lib myorg/mylib
# read project deps.edn
clojure -T:project read-project :lib myorg/mylib :fmjrey.project/verbose :very
# list all searched locations
clojure -T:project searched-deps :lib myorg/mylib
```

## Searched locations
By default project data is searched in the following locations in that order:

1. [Current basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/current-basis)
2. [Initial basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/initial-basis)
3. [Current `:basis-config` `:project` map](https://clojure.github.io/clojure/clojure.java.basis-api.html)
4. `deps.edn` content as provided by `tools.deps.edn/project-deps` API
   ([doc](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps)).
   This means `clojure.tools.deps.util.dir/with-dir` may be used to specify a
   custom project directory.
5. Custom `deps.edn` location as per the runtime current basis, using a file path
   created with the `:dir` and/or `:project` entries from `:basis-config`
   ([doc](https://clojure.org/reference/deps_edn#basis_config)), and loaded as
   a file then as a resource, unless it points to the default project  `deps.edn`
6. `deps.edn` as a file then as a resource
7. `/deps.edn` as a resource
8. `deps/<group-id>/<artifact-id>/deps.edn` as a resource
9. `/deps/<group-id>/<artifact-id>/deps.edn` as a resource

To change the searched locations and their order set the option
`:fmjrey.project/search-in` to one of, or a vector of:

- `:basis`: this corresponds to items 1 and 3 above
- `:project`: this corresponds to items 4 to 7 above
- `:resource`: this corresponds to items 8 and 9 above

For example to search only in the runtime basis:

```clojure
(def app-info
  (project/project-info {:lib 'my.app/name
                         :fmjrey.project/search-in :basis}))
```

The table below summarizes the applicability of each above location broken into
the corresponding `::search-in` option value and internal `::type`.
Disclaimer: there isn't at present a test case for each row, meaning this table
is made from the best knowledge of the author who would be happy to get
feedback and experience reports. Certainly rows having a note abbreviated by U
are not expected to be applicable in most cases, but may in some, and should
probably not be relied upon systematically unless it's the only option.
In any case passing a library symbol as a `:lib` parameter is the best way to
ensure more reliable results.
To save space the table uses abbreviations that are explained further below.

|#|Location|`::search-in`|as (`::type`)|Applicability|From|For|If|Notes|
|:--|:--|:--|:--|:--|:--|:--|:--|:--|
|1|[Current basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/current-basis)|`:basis`|`:current-basis`|RUN, DEV|P, D|P|CLI, SRC|BA|
|2|[Initial basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/initial-basis)|`:basis`|`:initial-basis`|RUN, DEV|P, D|P|CLI, SRC|BA|
|3|[Current `:basis-config` `:project` map](https://clojure.github.io/clojure/clojure.java.basis-api.html)|`:basis`|`:edn`|RUN, DEV|P, D|P||CLI?|
|4|[`clojure.tools.deps.edn/project-deps`](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps)|`:project`|`:edn`|RUN, DEV|P, D|P|SRC|WD|
|5|[Custom `deps.edn` path from current basis](https://clojure.org/reference/deps_edn#basis_config)|`:project`|`:file`|RUN, DEV|P, D|P|SRC|BC, CLI?|
|||`:project`|`:resource`|RUN|P|P||BC, CLI?, U, RO|
|6|`deps.edn`|`:project`|`:file`|RUN, DEV|P, D|P|SRC||
|||`:project`|`:resource`|RUN|P|P|JAR, UJAR|U, RO|
|||`:project`|`:resource`|RUN|D|I|JAR, DSRC|U, RO|
|7|`/deps.edn`|`:project`|`:resource`|RUN|P|P|JAR, UJAR|U, RO, RT|
|||`:project`|`:resource`|RUN|D|I|JAR, DSRC|U, RO, RT|
|8|`deps/<group-id>/<artifact-id>/deps.edn` |`:resource`|`:resource`|RUN|P, D|P, D|JAR, UJAR|RO|
|9|`/deps/<group-id>/<artifact-id>/deps.edn` |`:resource`|`:resource`|RUN|P, D|P, D|JAR, UJAR|RO, RT|

**Applicability abbreviations**

This table explains the applicability abbreviations with meaning to its right.

||Applicability|From|code in|For|getting project info of|If|launched/linked by|
|:--|:--|:--|:--|:--|:--|:--|:--|
|N/A|Not Applicable|P|dependent project|P|dependent project|CLI|clojure CLI (project source dir)|
|RUN|Runtime|D|dependency|D|a dependency |SRC|in source dir|
|DEV|Dev+Ops|||I|Itself|DSRC|deps from source (git or local, requires CLI)|
|||||||JAR|jar file (and maven/clojars deps)|
|||||||UJAR|uberjar file|

**Notes abbreviations**

|Abr.|Mnemonic|Notes|
|:--|:--|:--|
|BA|Basis|The runtime basis is set as a JVM system property by the clojure CLI host-specific scripts, and is cached on disk ([doc](https://clojure.org/reference/clojure_cli#cache_dir)).|
|CLI?|CLI needed?|The clojure CLI does not seem to be needed for this, this case is more likely set programmatically (e.g. testing, custom config, etc.)|
|WD|With Dir|The function `clojure.tools.deps.util.dir/with-dir` ([doc](https://cljdoc.org/d/org.clojure/tools.deps.edn/0.9.22/api/clojure.tools.deps.util.dir#with-dir)) may be used to specify a custom project directory.|
|U|Unlikely|Unlikely to be found unless explicitely added/copied in a resource root dir. Not really recommended but some existing project may have done so.|
|RO|Runtime Only|Intended for runtime use only. May also be picked up at dev/ops time, but other locations should be preferred and searched first.|
|BC|Basis Config|A file path is created with the `:dir` and/or `:project` entries from `:basis-config` ([doc](https://clojure.org/reference/deps_edn#basis_config)), and searched as a file then as a resource, unless it points to the default project `deps.edn`.|
|RT|RooT|A leading `/** designates the root of the classpath which may make sense in some environments and classloaders.|

**NOTE** The Excel spreadsheet that was used to build the tables above is
in this repository (xlsx file in root).

## Options

All API entry points can take an options map with the following optional entries:

- `:lib`: a qualified symbol identifying the project, in the format
  `groupId/artifactId` as expected under `:lib` by
  [write-pom](https://clojure.github.io/tools.build/clojure.tools.build.api.html#var-write-pom).
- `:fmjrey.project/search-in`: specifies the locations, and in which order, where
  to search for project info, which can be one of, or a vector of:
  
  - `:basis`: [current and initial basis](https://clojure.org/reference/deps_edn#basis)
  - `:project`: the project root
  - `:resource`: the resource directory
  
  See the [Searched locations](searched-locations) section for more details.
  Defaults to `[:basis :project :resource]`.
- `:fmjrey.project/alias`: the alias name under which project info is captured,
  which is also used as the key for storing the matching project data in the
  returned options map instead of the default `:project/info`.
- `:fmjrey.project/verbose`: when true the progression of the search is printed,
    and when set to `:very` it also prints the matching project entries.
- `:fmjrey.project/loader`: the classloader to also use for loading resources.
  The `project-info` macro adds the caller classloader automatically, if one is
  not already provided.
- `:fmjrey.project/resdir`: only used by `copy-deps` to specify the destination
  resource directory. Defaults to `"resources"`.

## Development (TODO Update this template generated section)

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
