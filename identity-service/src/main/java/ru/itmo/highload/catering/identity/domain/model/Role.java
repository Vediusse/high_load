package ru.itmo.highload.catering.identity.domain.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    private Short id;


    private RoleCode code;
}
