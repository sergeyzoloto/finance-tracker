import React from 'react';
import PropTypes from 'prop-types';

const Input = ({ name, value, onChange, ...rest }) => {
  return (
    <input
      {...rest}
      name={name}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
};

Input.propTypes = {
  name: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
};

export default Input;
