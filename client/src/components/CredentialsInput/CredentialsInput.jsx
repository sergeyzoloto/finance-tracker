import React from 'react';

import classNames from '../../utils/classNames';

/* Styles */
const styles = {
  INPUT_1: 'flexbox w-full p-2 rounded max-w-sm text-center',
  INPUT_2:
    'border-2 border-solid bg-white border-neutral-100 hover:border-neutral-200',
};

const CredentialsInput = ({ ...rest }) => {
  return (
    <input {...rest} className={classNames(styles.INPUT_1, styles.INPUT_2)} />
  );
};

export default CredentialsInput;
