package com.mysite.sbb.fastapi;

import java.util.List;

public record SeekSliceResponse<T>(List<T> content, boolean hasNext) {}