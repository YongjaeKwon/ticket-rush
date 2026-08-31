/**
 * 입장권 JWT 유틸. shared의 하위 패키지는 기본적으로 모듈 내부지만,
 * queue(발급)·reservation(검증)이 함께 쓰므로 이름 있는 인터페이스로 공개한다.
 */
@org.springframework.modulith.NamedInterface("token")
package com.ticketing.shared.token;
