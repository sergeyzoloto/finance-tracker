import React from 'react';
import PropTypes from 'prop-types';

const CredentialsInput = ({ name, value, onChange, ...rest }) => {
  return (
    <input
      {...rest}
      name={name}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
    />
  );
};

CredentialsInput.propTypes = {
  name: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
};

export default CredentialsInput;
