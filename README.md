# fmjrey/project

    fmjrey/project {:git/tag "TAG" :git/sha "SHA"}

Utility library to capture project info and retrieve it at runtime.

**WORK IN PROGRESS**

This library is in a pre-alpha state meaning the API is very likely to change.
Although the remit of capturing and retrieving project info appears simple,
the variety of ways in which clojure, and its derivatives beyond the JVM,
handle dependencies, their distribution, and artifact creation introduces some
complexity, and the need to offer flexibility and openness.

The difficulty with a hosted language is that the ecosystem it is hosted on most
likely already has tooling and formats for handling dependencies and project info.
There is however some value in having a uniform access to these, even if the data
comes from host-specific mechanisms. For example in polyglot projects it may be
useful to have access to all dependencies with version and license info to
analyze potential security and licensing issues.

## Library goals

Below are the goals for this library:

1. Initial focus is on capturing project info in `deps.edn` while allowing
  for other file and format because other host languages may have well defined
  ways to handle the same concern. Going beyond that initial focus largely
  depends on interest and contributions (see the [Customization](#customization)
  and [Feedback to/from the core team](#todo-get-feedback-from-clojure-core-team-and-provide-input) sections).
2. This library proposes some flexibility in the logic for retrieving project
  info at runtime (see [Options](#options) and [Customization](#customization)
  sections). It also provides the necessary logic and tools to prepare that
  info during development and build time. On the JVM this consists in copying
  the `deps.edn` file into a resource directory
  (see [Use within build.clj](#use-within-buildclj)).
3. There should be a clear distinction between the runtime and build time logic,
  so as to not force a project to bring dependencies at runtime that are only
  relevant during development (see [Usage](#usage) section).
4. Use the same simple API for clojure on the JVM and its derivatives hosted
  elsewhere. The function `info` and macro `project-info` are proposed to be
  that simple entry point. The reason for having a macro is because there may be
  logic to be run where the call is made (e.g. getting the caller classloader).
  Additional functions are there for customization, diagnosis, and experimentation
  (see [Usage](#usage) section).
5. It should be possible for a library existing as a dependency in a dependent
  project to retrieve its own project info as well as the info of the owning
  project. To that end the use of a project/library identifier is essential.
6. This library does not dictate what a project info is made of. It however
  assumes it is a single map with a mandatory project/library identifier
  under the `:id` key. The use of similar keys across languages is encouraged
  where it makes sense, see
  [`:project/info` alias entries](#projectinfo-alias-entries).
7. This library should depend on a limited set of functionality and dependencies
  and ideally have most of its logic in CLJC. At present it is written in `.clj`
  files because its initial focus makes it rely on
  [tools.deps.edn](https://github.com/clojure/tools.deps.edn).

## Rationale and design considerations

As the clojure CLI and build tools mature and become prevalent, the feature gap
with the previous leiningen tool is diminishing. Capturing project metadata
such as name and version remains however unhandled, and there aren't many places
where it can be.

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
may provide a way to access `deps.edn` but for now it does not. Moreover jar
artifacts do not usually contain the `deps.edn` unless it's copied in a
resource directory at build time.

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
   conflict with other user aliases. Therefore this library uses the qualified
   alias `:project/info`, and allows for it to be changed via some option.

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
   See the [Searched locations](#searched-locations) section.

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
   from one context to the other (e.g. uberjar, web or app server, etc.).
   Nevertheless the expectation for source dependencies is that there should
   always be a way to load `deps.edn` from project root, though some
   experimentation is needed to validate the most reliable way to do that.
   See the [Applicability for each location](#applicability-for-each-location)
   for some attempts to describe what's to be expected (feedback welcomed).

This library is therefore proposing to use `deps.edn` as the place where
project data is captured into a new `:project/info` alias. It also provides a
way to retrieve it at runtime by copying `deps.edn` into a resource directory.

## deps.edn :project/info alias

The `:project/info` alias in `deps.edn` contains a single map of data about
the project such as version, name, license, etc. Only the version in a project
root should be edited directly and version controlled. The copy that is made
in a resource directory should not be edited and preferably not checked in.

### :project/info alias entries

In `deps.edn`, project related data about the dependent side should be placed
in a map under the `:project/info` alias, and may contain the following entries:

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

A single namespace `fmjrey.project` contains the API for runtime, build, and CLI
usage. However to not load the clojure build API when using it at runtime,
`io.github.clojure/tools.build` is not declared as a dependency by this project,
and therefore must be declared as a dependency in yours, as illustrated
by the sample `deps.edn` below:

```clojure
{:deps {fmjrey/project {:git/tag "TAG" :git/sha "SHA"}} ;; runtime use
 :aliases {
   ;; easier to find as the first alias
   :project/info {:id: my.app/name
                  :name "my app name"
                  :license {:id "EPL-2.0"
                  :name "Eclipse Public License 2.0"
                  :url "https://www.eclipse.org/legal/epl-2.0"}}
   ;; CLI use with -X
   :project {:deps {;; tools.build should be declared here to use copy-deps
                    io.github.clojure/tools.build {:mvn/version "0.10.12"}
                    fmjrey/project {:git/tag "TAG" :git/sha "SHA"}}
             :exec-args {:fmjrey.project/verbose true} ; otherwise no printing
             :ns-default fmjrey.project}
   ;; build and task use with -T
   :build {:deps {;; tools.build must be declared here
                  io.github.clojure/tools.build {:mvn/version "0.10.12"}
                  fmjrey/project {:git/tag "TAG" :git/sha "SHA"}}
           :ns-default build}}}
```

Note how the above `deps.edn` defines 2 aliases that both declare
`io.github.clojure/tools.build` as a dependency:

- `:project`: for easier CLI use as it defaults to the `fmjrey.project`
  namespace and adds the verbose option (otherwise nothing is printed),
- `:build`: for use in `build.clj`.

### Use as a runtime library

Having access to project data can be useful for printing or logging information
at runtime, e.g. a header string containing name and version upon startup.
Most situations will only need the `project-info` macro or `info` function
to retrieve a project info. Additional functions are provided for more
customized behavior and experimentation.

In all cases the require entry should be as follows for runtime use:

```clojure
[fmjrey.project :as project]
```

#### Runtime `project-info` macro and `info` function

The easiest way to load project data is to use the macro `project/project-info`
or the `project/info` function in order to define a var to capture the
`:project/info` alias map, .e.g:

```clojure
(def app-info (project/project-info)) ;; the macro sets the classloader option
```
or
```clojure
(def app-info (project/info))
```

The `project-info` macro and `info` function have an identical signature and
represent the main API to retrieve a project information. Other functions provide
additional features that are mostly useful during development and experimentation.
The reason for having a macro on top of the function is to enable some logic to
be executed at the call site, namely the retrieval of the caller classloader
which can be useful for some use cases (TODO: validate this in testing).
The macro is therefore a convenience that avoids the manual setting of the
`:fmjrey.project/loader` [option](#options).

Both macro and function take an optional argument which can be a single library
symbol in the format `groupId/artifactId`, or an [options map](#options) which
may also specify a library symbol under the `:lib` key:

```clojure
(def app-info (project/project-info 'lib/name))
```
or

```clojure
(def app-info (project/project-info {:lib 'lib/name}))
```

Without any argument the owning project info is retrieved. With a library symbol
given as a single argument or within the options map, it may be possible under
certain conditions for a dependency to retrieve its own project info rather than
the info from the dependent project.
The locations where project data is searched for, and their applicability, are
detailed in the [Searched locations](#searched-locations) section.

When no symbol is given to `project-info` it can only search project data for the
running application in its runtime basis and project root directory and not in
a resource directory. It will also return the first project
data found regardless of its `:id`. For a more deterministic outcome it is best
to provide a symbol argument, and certainly necessary in the case of a library
code wishing to load its own project data instead of the dependent project data.

The search logic also tries to load a resource without specifying any
classloader, and then tries with an optional classloader if given with the
`:fmjrey.project/loader` option. The `project-info` macro adds the caller
classloader automatically, if not already provided.

A list of all possible options is detailed in the [Options](#options) section.

#### Additional runtime functions

In addition to the info macro and function the following functions take an options
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
  working keys and a `:project/info` entry when found.

For convenience at the command line, `print-project` and `print-searched-deps`
offer exactly the same functionality as their above counterparts, except they also
print to `stderr` the locations where a matching project entry is searched for.
To also print the matching project entries add `:fmjrey.project/verbose :very`
to the options map. Printing is in fact controlled by this option which is set
to `true` by the printing functions (unless `:very` is passed).

A list of all possible options is detailed in the [Options](#options) section.

### Use within build.clj

The most typical use of project info within `build.clj` is to create tasks
related to project versioning and release. For the latter the following function
is needed to create a copy of a project `deps.edn` to a library specific resource
directory:

- `copy-deps`: copy the project root `deps.edn` to a resource directory.
  Options map may have a `:lib` entry in the format `groupId/artifactId`
  and an optional `:fmjrey.project/resdir` to specify the destination resource
  directory (defaults to `"resources"`). Return the options map unchanged.
  Internally this function uses the `tools.deps.edn/project-deps-path` API
  ([doc](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps-path))
  to determine the path to the project `deps.edn`. Consequently the function
  [`clojure.tools.deps.util.dir/with-dir`](https://github.com/clojure/tools.deps.edn/blob/v0.9.22/src/main/clojure/clojure/tools/deps/util/dir.clj#L40),
  may be used to specify a custom project directory where to find the
  project `deps.edn` to copy, supporting custom setup such as polyfills or
  monorepos.

### Use from clojure CLI

Functions mentioned in the previous section can also be run using the clojure
CLI with the -T or -X option:

```
# copy project deps.edn to the resource directory
clojure -T:project copy-deps
> deps-edn-file ["deps.edn" file read-edn :aliases :project/info] available, found id my.app/name
Copying deps.edn to resources/deps/my/app/name/deps.edn

# read project deps.edn
clojure -X:project info
Searching for project info in alias :project/info
> deps-edn [current-basis :aliases :project/info] available, found id my.app/name
Found 1 matching source with :project/info

# read project deps.edn verbosely
clojure -X:project read-project :fmjrey.project/verbose :very
Searching for project info in alias :project/info
> deps-edn [current-basis :aliases :project/info] available, found id my.app/name:
  {:id my.app/name,
   :name "My project",
   :license
   {:id "EPL-2.0",
    :name "Eclipse Public License 2.0",
    :url "https://www.eclipse.org/legal/epl-2.0"}}
Found 1 matching source with :project/info in:
  deps-edn [current-basis :aliases :project/info]

# list all searched locations
clojure -X:project searched-deps
Searching for project info in alias :project/info
> deps-edn [current-basis :aliases :project/info] available, found id my.app/name
> deps-edn [initial-basis :aliases :project/info] available, found id my.app/name
> deps-edn [current-basis :basis-config :project :aliases :project/info] not available (nil :aliases)
> deps-edn [project-deps :aliases :project/info] available, found id my.app/name
> deps-edn-file ["deps.edn" file read-edn :aliases :project/info] available, found id my.app/name
Found 4 matching sources with :project/info
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
5. `deps.edn` as a file then as a resource
6. `/deps.edn` as a resource
7. `deps/<group-id>/<artifact-id>/deps.edn` as a resource
8. `/deps/<group-id>/<artifact-id>/deps.edn` as a resource

To change the searched locations and their order set the option
`:fmjrey.project/search-in` to one of, or a vector of:

- `:basis`: this corresponds to items 1 and 3 above
- `:project`: this corresponds to items 4 to 6 above
- `:resource`: this corresponds to items 7 and 8 above

For example to search only in the runtime basis:

```clojure
(def app-info
  (project/project-info {:lib 'my.app/name
                         :fmjrey.project/search-in :basis}))
```
### Applicability for each location

The table below summarizes the applicability of each above location along with
their corresponding `::search-in`, `::source`, and `::type` option values.
The description of these last 2 options can be found in the
[Customization](#customization) section.

Disclaimer: there isn't at present a test case for each row, meaning this table
is made from the best knowledge of the author who would be happy to get
feedback and experience reports. Certainly rows having a note abbreviated by `U`
are not expected to be applicable in most cases, but may in some, and should
probably not be relied upon systematically unless it's the only option.
In any case passing a library symbol as a `:lib` parameter is the best way to
ensure more reliable results.
To save space the table uses abbreviations that are explained further below.

|#|Location|::source|`::search-in`|as (`::type`)|Applicability|From|For|If|Notes|
|:--|:--|:--|:--|:--|:--|:--|:--|:--|:--|
|1|[Current basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/current-basis)|`['current-basis]`|`:basis`|`:deps-edn`|RUN, DEV|P, D|P|CLI, SRC|BA|
|2|[Initial basis](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/initial-basis)|`['initial-basis]`|`:basis`|`:deps-edn`|RUN, DEV|P, D|P|CLI, SRC|BA|
|3|[Current `:basis-config` `:project` map](https://clojure.github.io/clojure/clojure.java.basis-api.html)|`['current-basis :basis-config :project]`|`:basis`|`:deps-edn`|RUN, DEV|P, D|P||CLI?|
|4|[`clojure.tools.deps.edn/project-deps`](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps)|`['project-deps]`|`:project`|`:deps-edn`|RUN, DEV|P, D|P|SRC|WD|
|5|`deps.edn`|`["deps.edn"]`|`:project`|`:deps-edn-file`|RUN, DEV|P, D|P|SRC||
|||`["deps.edn"]`|`:project`|`:deps-edn-rsrc`|RUN|P|P|JAR, UJAR|U, RO|
|||`["deps.edn"]`|`:project`|`:deps-edn-rsrc`|RUN|D|I|JAR, DSRC|U, RO|
|6|`/deps.edn`|`["/deps.edn"]`|`:project`|`:deps-edn-rsrc`|RUN|P|P|JAR, UJAR|U, RO, RT|
|||`["/deps.edn"]`|`:project`|`:deps-edn-rsrc`|RUN|D|I|JAR, DSRC|U, RO, RT|
|7|`deps/<group-id>/<artifact-id>/deps.edn` |`["deps/<group-id>/<artifact-id>/deps.edn"]`|`:resource`|`:deps-edn-rsrc`|RUN|P, D|P, D|JAR, UJAR|RO|
|8|`/deps/<group-id>/<artifact-id>/deps.edn` |`["/deps/<group-id>/<artifact-id>/deps.edn"]`|`:resource`|`:deps-edn-rsrc`|RUN|P, D|P, D|JAR, UJAR|RO, RT|

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
|BA|Basis|[The runtime basis is set as a JVM system property by the clojure CLI host-specific scripts, and is cached on disk (doc).](https://clojure.org/reference/clojure_cli#cache_dir)|
|CLI?|CLI needed?|The clojure CLI does not seem to be needed for this, this case is more likely set programmatically (e.g. testing, custom config, etc.)|
|WD|With Dir|[The function `clojure.tools.deps.util.dir/with-dir` (doc) may be used to specify a custom project directory.](https://cljdoc.org/d/org.clojure/tools.deps.edn/0.9.22/api/clojure.tools.deps.util.dir#with-dir)|
|U|Unlikely|Unlikely to be found unless explicitely added/copied in a resource root dir. Not really recommended but some existing project may have done so.|
|RO|Runtime Only|Intended for runtime use only. A resource `deps.edn` may be picked up at dev/ops time, but other locations should be preferred and searched first.|
|RT|RooT|A leading `/` designates the root of the classpath which may make sense in some environments and classloaders.|

**NOTE** An Excel spreadsheet was used to build the tables above is
in this repository under `doc`. To convert it to markdown tables use the
[exceltk](https://github.com/fanfeilong/exceltk) tool.

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
  
  See the [Searched locations](#searched-locations) section for more details.
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

More internally used options are explained in the
[Customization](#customization) section.

## Customization

The options described in the [Options](#options) section should be the
first entry points for customization. In addition to these, more refined
customization can be achieved with the `::source` option that is used
internally as a "mini-DSL" for describing a single project info location.

This capability was originally created to have a unique location identifier
that also represent some of its constitutive parts, so as to enable finer
generative testing logic. The code simplification it provided led to its
evolution into a mini-DSL that drives the search for project info in all
cases.

Exposing it as a customization feature may offer all the advantages DSLs
can offer, notably a declarative approach, but also all the drawbacks in
terms of abuse and potential frustrations with its narrow scope.
It does offer some level of flexibility to deal with non-standard project
configurations, as well as other files and formats for capturing project
info that additional hosting platforms may already use.
Nevertheless it's being presented here to elicit feedback on its
relevance and possible usage.

### The `::source` option

The `::source` option is used internally to represent unambiguously a
location where to search for project info. It must contain a vector of
literal values where symbols and keywords respectively evaluate to some
some retrieval logic and map values. The vector is interpreted from left
to right as if its elements were threaded with `some->`, meaning the result
of one token is passed to the next and any `nil` result stops the threading.
Here are how literals are handled:

- Any literal that isn't of one of the type listed below just starts a new
  threading by ignoring the previous result and using itself as the result
  to be passed to the next step. For example many source vectors start with
  a string to be interpreted in the next step as a path to a `deps.edn` file.
- Keywords are applied as a function to the previous result in order to
  retrieve a map value.
- Symbols are interpreted in a big `case` statement to apply specific logic.
  In case of an unknown symbol it starts a new threading, ignoring the
  previous result and passing itself to the next step.
  Below is a table of currently interpreted symbols:
  
  |Symbol|Triggered logic|
  |:--|:--|
  |`current-basis`|[`clojure.java.basis/current-basis`](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/current-basis)|
  |`initial-basis`|[`clojure.java.basis/initial-basis`](https://clojure.github.io/clojure/clojure.java.basis-api.html#clojure.java.basis/initial-basis)|
  |`project-deps`|[`clojure.tools.deps.edn/project-deps`](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps)|
  |`file`|[`clojure.java.io/file`](https://clojuredocs.org/clojure.java.io/file)|
  |`resource`|[`clojure.java.io/resource`](https://clojuredocs.org/clojure.java.io/resource)|
  |`resource-cl`|[`clojure.java.io/resource` with classloader](https://clojuredocs.org/clojure.java.io/resource)|
  |`read-edn`|[`clojure.java.io/reader` + `clojure.tools.deps.edn/read-edn`](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps)|


The earlier [location applicability table](#applicability-for-each-location)
gives for each location the initial `::source` vector. It also gives the
internal `::type` of the `::source`, or more precisely the type of value that
should result from interpreting the corresponding `::source` vector.
Below are the current types that can be used:

|`::type`|`::source` must evaluate to|
|:--|:--|
|`:deps-edn`|Any map that contains an `:aliases` entry|
|`:deps-edn-file`|A string representing the path to a `deps.edn` file|
|`:deps-edn-rsrc`|A string representing the path to a `deps.edn` resource file|

The `::type` is also used to trigger additional logic before and after
interpreting the `::source` vector. It is currently used to add additional
elements to the `::source` vector, such as `:aliases` and `:project/info`
once a `deps.edn` map is loaded.
After interpretation it used to fine-tune or use the results, such as
adding new keys to the options map that will be returned.

For now there is no hook to handle additional `::type` or tokens in the
`::source` mini-DSL. The interpretation logic is hard-coded in the
`fmjrey.project/read-source` function. Therefore the `::source` option can
only be used to compose existing behavior, and an example of that can be
found in the `fmjrey.project/copy` function in order to get the
`deps.edn` path returned by
[`project-deps-path`](https://clojure.github.io/tools.deps.edn/#clojure.tools.deps.edn/project-deps-path)
(which works off the working directory set by
[with-dir](https://github.com/clojure/tools.deps.edn/blob/v0.9.22/src/main/clojure/clojure/tools/deps/util/dir.clj#L40),
meaning custom deps setup such as polyfills or monorepos can be supported by
calling that `with-dir` function before this library).

Depending on interest and contributions the addition of such hooks may
be considered, most likely using some additional options since multimethods,
protocols, or records, may not be available in all clojure derivatives,
whereas map literals are a defining feature of clojure that is unlikely to be
missing.

### Security considerations

Considering the recent increase in supply chain attacks, we should be extra
careful not to augment the risk surface. The limitations of using a very simple
`::source` mini-DSL may lead to the temptation of increasing its expressiveness.
This is exactly how the risk surface can be increased if not careful.

In particular, one obvious addition would be to interpret symbols as references
to vars to be resolved at runtime. This increases the attack surface by
providing a way to execute arbitrary code provided externally, ultimately
pointing to the level of trust we can have on that code, and the mechanisms to
bring it into the execution context.

We could restrict this feature to standard clojure tooling namespaces such as
[`clojure.tools.deps.edn`](https://clojure.github.io/tools.deps.edn/) and
[`clojure.tools.build.api`](https://clojure.github.io/tools.build/).
The former is already required by this library in all cases, so it should not
expand the risk surface. The second however isn't, unless within build code.
So can something in non-build code fill the gap and parade as the build API?
Certainly one should prefer the use of
[`resolve`](https://clojuredocs.org/clojure.core/resolve) over
[`requiring-resolve`](https://clojuredocs.org/clojure.core/requiring-resolve)
so as to not go beyond what's already required at runtime.

Overall, if this mini-DSL remains an official way to customize the behavior of
retrieving project info, we should strive to keep it simple and secure.
As mentioned earlier, it is explained here to elicit feedback and debate.

## TODOs

Below are some work items remaining before some proper release:

#### TODO Testing

More tests are needed, and in particular following the cases outlined in the
[location applicability table](#applicability-for-each-location).
An initial test framework with test projects inside the `test-data` folder
is in place, but such fixture requires launching the clojure CLI externally,
which is rather costly if done for each test case.

A more appropriate logic would be to have the code in these test projects
run their own test cases and aggregate them back into this project testing.
There is some scaffolding that can help:

- Each test project has the same code for invoking a function either in its
  own context, or by delegation in another test project added as a dependency.
  This is to enable testing when this project functions are called from both
  the owning project code and from a dependency code.
- The test code in this project has reproduced a simple version of the clojure
  [protocol](https://clojure.org/reference/clojure_cli#function_protocol)
  to programmatically invoke tools or functions externally via the clojure CLI.
  On the caller side it unwraps the envelope created by the callee side.
  In clojure these are respectively implemented in
  [`tools.deps`](https://github.com/clojure/clojure/blob/clojure-1.12.4/src/clj/clojure/tools/deps/interop.clj#L41)
  and the `exec.jar` from the clojure CLI which code seems to be
  [here](https://github.com/clojure/brew-install/blob/1.12.4/src/main/clojure/clojure/run/exec.clj#L52).
  However this clojure feature only works for invoking a project's own tools at
  runtime, and not another project or dependency tool or function.

#### TODO Documentation

Add documentation in the form of docstrings. This README is the only
form of documentation at present, as it started to be written before any
code, playing the role of a specification. Once the API is reasonably
stable proper docstrings should be added.

#### TODO Get feedback from clojure core team and provide input

This library overlaps with some intended work the clojure core team is pursuing
or planning to pursue. In particular, there is an intent to make the clojure
tooling more useful to, and support, different clojure dialects. This probably
depends on the overall strategy for clojure evolution in relation to the various
dialects it has generated. At the time of writing these workgroups are barely
starting and won't provide definite feedback except some early hints.
Nevertheless feedback from the core team will be essential for the evolution and
stabilization of this library, which still needs to provide a sensible way to
handle project info until such time there is an official one.

Until then here is the following feedback to the core team:

- **Provide jar dependencies access to their own `deps.edn`**: this would
  probably be a new feature in the jar building logic.
  JIRA [TDEPS-277](https://clojure.atlassian.net/browse/TDEPS-277)
  and [TDEPS-278](https://clojure.atlassian.net/browse/TDEPS-278) hint at copying
  `deps.edn` as a resource file, and this project follows that idea.
- **Provide source dependencies access to their own `deps.edn`**: this would be
  a new feature to be provided by the clojure deps runtime. The alternative is
  using the `deps.edn` resource copy, but this would force having that copy in
  source control, which opens a can of worms in terms of sync issues.
- **Provide a way to invoke externally any function from any project**:
  the testing of this project required an enhanced copy of the clojure
  [protocol](https://clojure.org/reference/clojure_cli#function_protocol)
  to programmatically invoke functions in an external process via the clojure
  CLI, see [fmjrey/invoke](https://github.com/fmjrey/invoke) for more details.
  This is to ensure the function is executed with a runtime basis, working
  directory, and `deps.edn`, that are different from the calling project, while
  [`invoke-tool`](https://clojuredocs.org/clojure.tools.deps.interop/invoke-tool)
  from clojure uses the runtime basis and `deps.edn` of the calling project.

The last two items may hint at a more general feature for the clojure runtime to
direct some of its logic to any project directory, or one of its dependency, and
not just its own.

#### TODO Stabilize the API

For this library evolution towards supporting more clojure dialects, the API and
terminology needs to be more generic and not specific to clojure on the JVM.
While the clojure core team will most likely be expanding the use of `deps.edn`,
it is not guaranteed that a project info is best placed in that file. For
integration purposes it may make more sense to leave it in a host-specific place.
Also dependencies may be coming from the host ecosystem itself, meaning not
written in a clojure dialect, and therefore without any `deps.edn` file.
Therefore the current use of the word `deps` in this project API may be too
specific and some review is likely to be needed at some point.

#### TODO Release artifact

A library jar on clojars and some source dependency version mentioned in
this README.

#### TODO Support some clojure derivatives

Convert some code into CLJC and add support for other clojure derivatives.

## Development

Invoke a library API function from the command-line:

    $ clojure -X fmjrey.project/info :fmjrey.project/verbose :very
    > deps-edn [current-basis :aliases :project/info] available, found id fmjrey/project:
      {:id fmjrey/project,
       :name "project",
       :license
       {:id "EPL-2.0",
        :name "Eclipse Public License 2.0",
        :url "https://www.eclipse.org/legal/epl-2.0"}}
    Found 1 matching source with :project/info in:
      deps-edn [current-basis :aliases :project/info]


Run the project's tests:

    $ clojure -M:test

**THE REST OF THIS SECTION WAS GENERATED BY THE TEMPLATE**

**TODO Update it!**

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
