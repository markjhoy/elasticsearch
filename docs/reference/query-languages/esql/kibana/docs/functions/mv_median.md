% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### MV MEDIAN
Converts a multivalued field into a single valued field containing the median value.

```esql
ROW a=[3, 5, 1]
| EVAL median_a = MV_MEDIAN(a)
```
