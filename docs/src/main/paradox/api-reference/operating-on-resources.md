# Operating on resources

All resources in the system share a base set of operations.  Assuming a nexus deployment at
`http(s)://nexus.example.com` resource address of `/v0/{address}` the following operations should apply to most (all)
resources:

### Fetch the current revision of the resource

```
GET /v0/{address}
```

#### Status Codes

- **200 OK**: the resource is found and returned successfully
- **404 Not Found**: the resource was not found

### Fetch a specific revision of the resource

```
GET /v0/{address}?rev={rev}
```
... where `rev` is the revision number, starting at `1`.

#### Status Codes

- **200 OK**: the resource revision is found and returned successfully
- **404 Not Found**: the resource revision was not found

### Fetch a resource history

Returns the collection of changes performed on the resource (the deltas).

```
GET /v0/{address}/history
```

#### Status Codes

- **200 OK**: the resource is found and its history is returned successfully
- **404 Not Found**: the resource was not found

### Create a new resource

Depending on whether the resource is a singleton resource or is part of a wider collection of resources of the same
type the verbs `POST` and `PUT` are used.

For a singleton resource:

```
PUT /v0/{address}
{...}
```

For a collection resources:

```
POST /v0/{collection_address}
{...}
```
... where `collection_address` is the address of the collection the resource belongs to.

#### Status Codes

- **201 Created**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the resource already exists

### Update a resource

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v0/{address}?rev={previous_rev}
{...}
```

#### Status Codes

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the provided revision is not the current resource revision number

### Partially update a resource

A partial update is still an update, so the last revision needs to be passed as a query parameter as well.

```
PATCH /v0/{address}?rev={previous_rev}
{...}
```

#### Status Codes

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the provided revision is not the current resource revision number

### Deprecate a resource

Deprecating a resource is considered to be an update as well.

```
DELETE /v0/{address}?rev={previous_rev}
```

#### Status Codes

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the provided revision is not the current resource revision number

## Search and filtering

All collection resources support full text search with filtering and pagination and present a consistent data model
as the result envelope.

General form:
```
GET /v0/{collection_address}
      ?q={full_text_search_query}
      &filter={filter}
      &from={from}
      &size={size}
      &deprecated={deprecated}
```
... where all of the query parameters are individually optional.

- `{collection_address}` is the selected collection to list, filter or search; for example: `/v0/data`, `/v0/schemas`,
`/v0/data/myorg/mydomain`
- `{full_text_search_query}`: String - can be provided to select only the resources in the collection that have
attribute values matching (containing) the provided token; when this field is provided the results will also include
score values for each result
- `{filter}`: JsonLd - a filtering expression in JSON-LD format (the structure of the filter is explained below)
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status

Filtering example while listing:

List Instances
: @@snip [list-instances.txt](../assets/api-reference/list-instances.txt) { type=shell }

List Instances Response
:   @@snip [instance-list.json](../assets/api-reference/instance-list.json)

Filtering example while searching (notice the additional score related fields):

Search and Filter Instances
: @@snip [search-list-instances.txt](../assets/api-reference/search-list-instances.txt) { type=shell }

Search and Filter Instances Response
:   @@snip [instance-list.json](../assets/api-reference/instance-search-list.json)

### Filter expressions

Filters follow the general form:

```
comparisonOp    ::= 'eq' | 'ne' | 'lt' | 'lte' | 'gt' | 'gte'
logicalOp       ::= 'and' | 'or' | 'not' | 'xor'
op              ::= comparisonOp | logicalOp

path            ::= uri
comparisonValue ::= literal | uri | {comparisonValue}

comparisonExpr  ::= json {
                      "op": comparisonOp,
                      "path": path,
                      "value": comparisonValue
                    }

logicalExpr     ::= json {
                      "op": logicalOp,
                      "value": {filterExpr}
                    }

filterExpr      ::= logicalExpr | comparisonExpr

filter          ::= json {
                      "@context": {...},
                      "filter": filterExpr
                    }
```
... which roughly means:

- a filter is a json-ld document
- with a user defined context
- that describes a filter value as a filter expression
- a filter expression is either a comparison expression or a logical expression
- a comparison expression contains a path property (currently restricted to an uri), the value to compare and an
  operator which describes how to compare that value
- a logical expression contains a collection of filter expressions joined together through a logical operator

Before evaluating the filter, the json-ld document is provided with an additional default context that overrides any
user defined values in case of collisions:
```
{
  "@context": {
    "nxv": "https://nexus.example.com/v0/voc/nexus/core/",
    "nxs": "https://nexus.example.com/v0/voc/nexus/search/",
    "path": "nxs:path",
    "op": "nxs:operator",
    "value": "nxs:value",
    "filter": "nxs:filter"
  }
}
```

Example filters:

Comparison
:   @@snip [simple-filter.json](../assets/api-reference/simple-filter.json)

With context
:   @@snip [simple-filter-with-context.json](../assets/api-reference/simple-filter-with-context.json)

Nested filter
:   @@snip [nested-filter.json](../assets/api-reference/nested-filter.json)

## Error Signaling

The services makes use of the HTTP Status Codes to report the outcome of each API call.  The status codes are
complemented by a consistent response data model for reporting client and system level failures.

Format
:   @@snip [error.json](../assets/api-reference/error.json)

Example
:   @@snip [error-example.json](../assets/api-reference/error-example.json)

While the format only specifies `code` and `message` fields, additional fields may be presented for additional
information in certain scenarios.