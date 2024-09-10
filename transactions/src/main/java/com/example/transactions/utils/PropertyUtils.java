package com.example.transactions.utils;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.lang.reflect.Field;

public class PropertyUtils {

  // Private constructor to prevent instantiation
  private PropertyUtils() {
      throw new UnsupportedOperationException("Utility class");
  }

  public static void copyNonNullProperties(Object src, Object target) {
      BeanWrapper srcWrapper = new BeanWrapperImpl(src);
      BeanWrapper targetWrapper = new BeanWrapperImpl(target);

      for (Field field : src.getClass().getDeclaredFields()) {
          Object srcValue = srcWrapper.getPropertyValue(field.getName());
          if (srcValue != null) {
              targetWrapper.setPropertyValue(field.getName(), srcValue);
          }
      }
  }
}