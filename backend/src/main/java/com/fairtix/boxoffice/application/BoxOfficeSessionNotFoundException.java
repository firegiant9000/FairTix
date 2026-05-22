package com.fairtix.boxoffice.application;

import com.fairtix.common.ResourceNotFoundException;

public class BoxOfficeSessionNotFoundException extends ResourceNotFoundException {
  public BoxOfficeSessionNotFoundException(String message) {
    super(message);
  }
}
