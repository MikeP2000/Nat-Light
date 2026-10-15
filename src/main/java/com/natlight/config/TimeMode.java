package com.natlight.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TimeMode
{
	CYCLE("Day/night cycle"),
	STATIC("Static time");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
