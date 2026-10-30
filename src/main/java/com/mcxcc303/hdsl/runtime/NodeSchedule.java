package com.mcxcc303.hdsl.runtime;

import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;

public record NodeSchedule(@Nullable LocalDate alpha, LocalDate start, @Nullable LocalDate lts,
                           @Nullable LocalDate maintenance, LocalDate end, @Nullable String codename) {}
